package com.leyon.soul.domain.content;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;

/** H-04 内容运营类型：路径参数与草稿/快照共用。 */
public enum ContentType {
    KG, SCENE, EXERCISE;

    public static ContentType parse(String raw) {
        if (raw == null) throw new BizException(ErrorCode.BAD_PARAMS, "缺少内容类型");
        return switch (raw.trim().toLowerCase()) {
            case "kg" -> KG;
            case "scene", "scenes" -> SCENE;
            case "exercise", "exercises" -> EXERCISE;
            default -> throw new BizException(ErrorCode.BAD_PARAMS, "未知内容类型: " + raw);
        };
    }
}
