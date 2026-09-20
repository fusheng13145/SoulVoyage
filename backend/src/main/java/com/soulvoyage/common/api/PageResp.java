package com.soulvoyage.common.api;

import org.springframework.data.domain.Page;

import java.util.List;

/** O2 统一分页响应：全站分页端点收敛为同一形状 {items,page,size,total} */
public record PageResp<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResp<T> of(Page<T> p) {
        return new PageResp<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    /** 非 Page 查询（如派生查询 + Pageable 取一页）手工组装时用 */
    public static <T> PageResp<T> of(List<T> items, PageReq req, long total) {
        return new PageResp<>(items, req.page(), req.size(), total);
    }
}
