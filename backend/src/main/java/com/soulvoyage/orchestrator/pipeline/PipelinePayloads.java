package com.soulvoyage.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;

/** 流水线入参边界校验：只判结构与类型，不承载业务规则。 */
public final class PipelinePayloads {

    private PipelinePayloads() {}

    public static JsonNode requireObject(JsonNode payload) {
        if (payload == null || !payload.isObject()) throw invalid("payload 必须是 JSON 对象");
        return payload;
    }

    public static JsonNode requireObjectField(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || !v.isObject()) throw invalid(field + " 必须是 JSON 对象");
        return v;
    }

    /** 字段可缺省；存在时必须是文本（空串放行，保持既有降级语义）。 */
    public static void requireTextIfPresent(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v != null && !v.isTextual()) throw invalid(field + " 必须是文本");
    }

    public static void requirePositiveLong(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || !v.isIntegralNumber() || v.asLong() <= 0) throw invalid(field + " 必须是正整数");
    }

    public static void requireNonBlankText(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw invalid(field + " 不能为空");
    }

    public static void requirePositiveInt(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || !v.isIntegralNumber() || v.asInt() < 1) throw invalid(field + " 必须是正整数");
    }

    private static BizException invalid(String msg) {
        return new BizException(ErrorCode.BAD_PARAMS, msg);
    }
}
