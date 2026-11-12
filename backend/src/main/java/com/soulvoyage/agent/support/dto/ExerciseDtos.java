package com.soulvoyage.agent.support.dto;

/** 练习写接口的响应契约。 */
public class ExerciseDtos {

    /** 跟练打卡回执（completed 沿用既有语义：写入成功即视为已记录）。 */
    public record CheckInResp(Long id, String exerciseId, boolean completed) {}
}
