package com.leyon.soul.domain.content.dto;

import java.util.List;

/** 每日一读读接口的响应契约。 */
public class ReadingDtos {

    public record TodayResp(String kgNodeId, String title, String summary, String microAction,
                            List<String> aboutTags, int readingSec, String date,
                            boolean favorited, boolean firstToday) {}

    /** 收藏开关回执：返回操作后的收藏态。 */
    public record FavoriteToggleResp(String type, String refCode, boolean favorited) {}
}
