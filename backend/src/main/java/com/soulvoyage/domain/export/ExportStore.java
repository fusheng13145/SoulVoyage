package com.soulvoyage.domain.export;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 导出快照的一次性暂存处（S2 可携带权 / A5 归档导出共用）：
 * "限时 5 分钟 + 领取即焚"的语义由实现保证，调用方只管 put/take。
 * 进程内版（默认）快照只在内存——重启最坏丢一条链接；
 * Redis 版（M13，多节点）落 **信封加密后的密文**：明文从不出进程，
 * 密钥销毁（注销即遗忘）后残留字节永久不可解。
 *
 * take 把属主断言做进"取出"这一步：只有属主本人取走才销毁快照，
 * 别人拿着泄漏的链接抢先访问只会得到 403，链接本体留给属主（原 UC5 口径）。
 */
public interface ExportStore {

    void put(String fileId, long userId, ObjectNode data);

    Taken take(String fileId, long requesterId);

    enum Outcome { MISSING, FOREIGN, CLAIMED }

    record Taken(Outcome outcome, ObjectNode data) {
        public static final Taken MISSING = new Taken(Outcome.MISSING, null);
        public static final Taken FOREIGN = new Taken(Outcome.FOREIGN, null);
    }
}
