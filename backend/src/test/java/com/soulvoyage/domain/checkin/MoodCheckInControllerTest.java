package com.soulvoyage.domain.checkin;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 打卡端点的转发与入参校验：month 解析失败必须收口成 400（BAD_PARAMS）而非 500。 */
@ExtendWith(MockitoExtension.class)
class MoodCheckInControllerTest {

    @Mock CheckInService checkIn;
    @Mock StreakService streak;
    @InjectMocks MoodCheckInController controller;

    private AuthPrincipal principal() {
        AuthPrincipal p = mock(AuthPrincipal.class);
        lenient().when(p.userId()).thenReturn(7L);
        return p;
    }

    private MoodCheckInController.CheckInReq req() {
        return new MoodCheckInController.CheckInReq("HAPPY", 4, 3, "今天还行");
    }

    @Test
    @DisplayName("POST 打卡：日期与请求体原样转发给服务层")
    void checkInForwards() {
        LocalDate date = LocalDate.of(2026, 9, 29);
        when(checkIn.checkIn(eq(7L), eq(date), any())).thenReturn(null);

        assertNotNull(controller.checkIn(principal(), date, req()));

        ArgumentCaptor<CheckInService.CheckInReq> captor =
                ArgumentCaptor.forClass(CheckInService.CheckInReq.class);
        verify(checkIn).checkIn(eq(7L), eq(date), captor.capture());
        assertEquals("HAPPY", captor.getValue().emotion());
        assertEquals(4, captor.getValue().rating());
        assertEquals("今天还行", captor.getValue().note());
    }

    @Test
    @DisplayName("POST 打卡：不带 date 时透传 null，由服务层按业务时区取今天")
    void checkInWithoutDate() {
        when(checkIn.checkIn(eq(7L), eq(null), any())).thenReturn(null);

        controller.checkIn(principal(), null, req());

        verify(checkIn).checkIn(eq(7L), eq(null), any());
    }

    @Test
    @DisplayName("GET 月视图：正常 YYYY-MM 时同时回 items 与 today")
    void monthReturnsItemsAndToday() {
        CheckInService.CheckInView view = new CheckInService.CheckInView(
                LocalDate.of(2026, 9, 29), "HAPPY", 4, 3, "今天还行", false, new BigDecimal("0.8"));
        when(checkIn.month(7L, 2026, 9)).thenReturn(List.of(view));
        // today 回的是"今天这条记录"，未打卡时为 null（返回类型不是 boolean）
        when(checkIn.today(7L)).thenReturn(view);

        Map<String, Object> out = controller.month(principal(), "2026-09").getData();

        assertEquals(List.of(view), out.get("items"));
        assertEquals(view, out.get("today"));
    }

    @Test
    @DisplayName("GET 月视图：今天尚未打卡时 today 为 null，items 照常返回")
    void monthWithNoCheckInToday() {
        when(checkIn.month(7L, 2026, 9)).thenReturn(List.of());
        when(checkIn.today(7L)).thenReturn(null);

        Map<String, Object> out = controller.month(principal(), "2026-09").getData();

        assertEquals(List.of(), out.get("items"));
        assertEquals(null, out.get("today"));
    }

    @Test
    @DisplayName("GET 月视图：缺段的 month 收口成 400 而非 500")
    void monthMalformedIsBadParams() {
        BizException ex = assertThrows(BizException.class,
                () -> controller.month(principal(), "202609"));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("GET 月视图：非数字的 month 同样收口成 400")
    void monthNonNumericIsBadParams() {
        BizException ex = assertThrows(BizException.class,
                () -> controller.month(principal(), "abc-def"));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("GET streak：转发到 StreakService")
    void streakForwards() {
        when(streak.compute(7L)).thenReturn(new StreakService.StreakView(3, 5, 12, true));

        assertNotNull(controller.streak(principal()));

        verify(streak).compute(7L);
    }

    @Test
    @DisplayName("POST 补签：转发日期与请求体")
    void makeupForwards() {
        LocalDate date = LocalDate.of(2026, 9, 27);
        when(checkIn.makeup(eq(7L), eq(date), any())).thenReturn(null);

        controller.makeup(principal(), date, req());

        verify(checkIn).makeup(eq(7L), eq(date), any());
    }

    @Test
    @DisplayName("POST 补签：服务层拒绝时异常原样上抛（不做二次包装）")
    void makeupPropagatesRejection() {
        when(checkIn.makeup(eq(7L), any(), any()))
                .thenThrow(new BizException(ErrorCode.BAD_PARAMS, "补签只支持最近 14 天内"));

        assertThrows(BizException.class,
                () -> controller.makeup(principal(), LocalDate.of(2026, 1, 1), req()));
        verify(checkIn, org.mockito.Mockito.never()).checkIn(anyInt(), any(), any());
    }
}
