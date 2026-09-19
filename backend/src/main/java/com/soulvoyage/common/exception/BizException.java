package com.soulvoyage.common.exception;

import com.soulvoyage.common.api.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode ec) {
        this(ec, null);
    }

    public BizException(ErrorCode ec, String msg) {
        super(msg == null ? ec.defaultMsg() : msg);
        this.errorCode = ec;
    }
}
