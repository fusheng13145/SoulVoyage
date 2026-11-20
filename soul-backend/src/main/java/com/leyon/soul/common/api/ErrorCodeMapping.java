package com.leyon.soul.common.api;

import org.springframework.http.HttpStatus;

/**
 * 错误码 → HTTP 状态唯一映射表（B-19）。
 *
 * <p>使用穷尽 switch：新增 {@link ErrorCode} 常量而忘记登记时直接编译失败，
 * 避免运行期悄悄落进某个 default 分支。码值只增不改，历史语义保持不变。
 */
public final class ErrorCodeMapping {

    private ErrorCodeMapping() {}

    public static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED, LOGIN_FAILED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CRISIS_MODE_RESTRICTED -> HttpStatus.FORBIDDEN;
            case USERNAME_EXISTS, TASK_CONCURRENCY_LIMIT, CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND, TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LLM_RATE_LIMIT, RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case LLM_TIMEOUT, DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ASR_FAILED, WX_LOGIN_FAILED, LLM_OUTPUT_INVALID -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            // 20xx 参数与 30xx 流程类，以及 1006 绑定过期
            case BAD_PARAMS, PIPELINE_NOT_ALLOWED, WX_BIND_EXPIRED -> HttpStatus.BAD_REQUEST;
        };
    }
}
