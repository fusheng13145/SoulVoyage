package com.leyon.soul.domain.letter.dto;

import java.util.List;

/** 成长来信读接口的响应契约。 */
public class LetterDtos {

    public record LetterItem(String id, String statWeek, String createdAt, String letter, String weekGlow) {}

    public record LetterListResp(List<LetterItem> items, int total) {}
}
