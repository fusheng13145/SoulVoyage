package com.soulvoyage.orchestrator;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;

/**
 * 流水线身份编码（B-04）：核心层强类型，HTTP 边界仍以字符串交互。
 * 新增流水线必须同时实现 {@code TaskDefinition}，否则启动校验失败。
 */
public enum PipelineCode {
    DIARY_PIPELINE,
    COGNITIVE_PIPELINE,
    SIMULATE_PIPELINE,
    COMPANION_PIPELINE,
    GROWTH_LETTER_PIPELINE;

    /** 边界解析：空值/未知值在提交时立即失败（3002），不再留到异步步骤。 */
    public static PipelineCode from(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (PipelineCode code : values()) {
                if (code.name().equals(raw)) return code;
            }
        }
        throw new BizException(ErrorCode.PIPELINE_NOT_ALLOWED, "未注册的流水线: " + raw);
    }
}
