package com.soulvoyage.common.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 下篇·O1 业务时区统一：一切"用户可感知的日期"（记录日、周画像、报告标题、查询默认边界）
 * 都取 soulvoyage.business-zone，而非 JVM 默认时区；Instant/审计时间戳仍用 UTC。
 */
@Component
public class BusinessCalendar {

    private final ZoneId zone;

    public BusinessCalendar(@Value("${soulvoyage.business-zone:Asia/Shanghai}") String businessZone) {
        this.zone = ZoneId.of(businessZone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public ZoneId zone() {
        return zone;
    }
}
