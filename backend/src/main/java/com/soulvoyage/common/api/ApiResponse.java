package com.soulvoyage.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.MDC;

@Data
@AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String msg;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, traceId());
    }

    public static <T> ApiResponse<T> fail(ErrorCode ec, String msg) {
        return new ApiResponse<>(ec.code(), msg == null ? ec.defaultMsg() : msg, null, traceId());
    }

    private static String traceId() {
        String t = MDC.get("traceId");
        return t == null ? "" : t;
    }
}
