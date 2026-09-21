package com.soulvoyage.common.api;

/** 错误码分段：10xx 认证 / 20xx 参数 / 30xx 任务 / 40xx 模型网关（推理与转写）/ 50xx 风控 */
public enum ErrorCode {
    UNAUTHORIZED(1001, "未登录或凭证失效"),
    FORBIDDEN(1002, "无权访问该资源"),
    LOGIN_FAILED(1003, "用户名或密码错误"),
    USERNAME_EXISTS(1004, "用户名已存在"),
    BAD_PARAMS(2001, "参数不合法"),
    NOT_FOUND(2002, "资源不存在"),
    TASK_NOT_FOUND(3001, "任务不存在"),
    PIPELINE_NOT_ALLOWED(3002, "流水线不允许"),
    TASK_CONCURRENCY_LIMIT(3003, "并发任务超限"),
    LLM_TIMEOUT(4001, "模型服务超时，请稍后再试"),
    LLM_RATE_LIMIT(4002, "请求过于频繁"),
    LLM_OUTPUT_INVALID(4003, "模型输出校验失败"),
    ASR_FAILED(4004, "语音转写失败，请稍后再试"),
    CRISIS_MODE_RESTRICTED(5001, "当前处于关怀模式，功能受限");

    private final int code;
    private final String defaultMsg;

    ErrorCode(int code, String defaultMsg) {
        this.code = code;
        this.defaultMsg = defaultMsg;
    }

    public int code() { return code; }
    public String defaultMsg() { return defaultMsg; }
}
