package com.soulvoyage.llm;

/**
 * 结构化输出下的增量取值器：模型边吐 JSON、用户边看到字段文本。
 * 只外发**顶层**目标字符串字段的解码内容，其余（围栏噪声、兄弟字段、嵌套结构）一概不透出；
 * Schema 校验仍在末端对完整响应做一次，这里不参与正确性判定，只负责体感首 token 延迟。
 * 非线程安全：一次流式调用 new 一个。
 */
public final class StreamedJsonField {

    private final String key;
    private final StringBuilder out = new StringBuilder();
    private final StringBuilder strBuf = new StringBuilder();
    private final StringBuilder codeBuf = new StringBuilder();

    private int depth;
    private boolean inString;
    private boolean keyRole;        // 当前字符串是顶层字段名
    private boolean expectKey = true;  // 顶层下一个字符串是字段名（'{' 或 ',' 之后）
    private boolean pendingMatch;   // 刚读完的顶层字段名命中目标，下一个顶层字符串即其值
    private boolean inValue;
    private boolean esc;
    private boolean unicode;
    private boolean done;

    public StreamedJsonField(String key) {
        this.key = key;
    }

    /** 目标字段是否已完整交付（此后不再外发） */
    public boolean isDone() {
        return done;
    }

    /** 喂入一段裸增量，返回本次可外发的解码文本（可能为空串） */
    public String feed(String delta) {
        out.setLength(0);
        if (delta == null || done) return "";
        for (int i = 0; i < delta.length() && !done; i++) step(delta.charAt(i));
        return out.toString();
    }

    private void step(char c) {
        if (inValue) {
            valueChar(c);
            return;
        }
        if (inString) {
            if (c == '"') {
                inString = false;
                if (keyRole) {
                    pendingMatch = strBuf.toString().equals(key);
                    keyRole = false;
                }
            } else {
                strBuf.append(c);
            }
            return;
        }
        switch (c) {
            case '{', '[' -> {
                depth++;
                if (depth == 1 && c == '{') expectKey = true;
            }
            case '}', ']' -> {
                if (depth == 1) done = true;   // 顶层对象收尾：字段没流出来就是没流出来，交给末端校验
                depth--;
            }
            case ',' -> {
                if (depth == 1) {
                    expectKey = true;
                    pendingMatch = false;      // 上一个顶层值是非字符串（数字/布尔/null）
                }
            }
            case ':' -> {
                if (depth == 1) expectKey = false;
            }
            case '"' -> {
                strBuf.setLength(0);
                inString = true;
                if (depth == 1) {
                    if (expectKey) keyRole = true;
                    else if (pendingMatch) inValue = true;
                }
            }
            default -> { }
        }
    }

    private void valueChar(char c) {
        if (unicode) {
            codeBuf.append(c);
            if (codeBuf.length() == 4) {
                try {
                    out.append((char) Integer.parseInt(codeBuf.toString(), 16));
                } catch (NumberFormatException e) {
                    out.append(c);   // 畸形转义：原样带出，别把用户的字吞了
                }
                codeBuf.setLength(0);
                unicode = false;
            }
            return;
        }
        if (esc) {
            esc = false;
            switch (c) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    unicode = true;
                    codeBuf.setLength(0);
                }
                default -> out.append(c);   // \" \\ \/ 以及供应商自造的转义
            }
            return;
        }
        if (c == '\\') {
            esc = true;
        } else if (c == '"') {
            inValue = false;
            done = true;
        } else {
            out.append(c);
        }
    }
}
