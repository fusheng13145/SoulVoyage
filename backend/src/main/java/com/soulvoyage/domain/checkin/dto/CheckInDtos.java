package com.soulvoyage.domain.checkin.dto;

import com.soulvoyage.domain.checkin.CheckInService;

import java.util.List;

/** 心情打卡月视图读接口的响应契约（today 可为 null，表示今天尚未打卡）。 */
public class CheckInDtos {

    public record MonthResp(List<CheckInService.CheckInView> items, CheckInService.CheckInView today) {}
}
