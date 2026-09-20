package com.soulvoyage.common.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** O2 统一分页入参：page 从 0 起，size 钳制到 [1, MAX_SIZE]（非法输入静默归位而非报错） */
public record PageReq(int page, int size) {

    public static final int MAX_SIZE = 50;

    public static PageReq of(Integer page, Integer size) {
        return new PageReq(page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? 10 : Math.min(size, MAX_SIZE));
    }

    public Pageable toRequest() {
        return PageRequest.of(page, size);
    }
}
