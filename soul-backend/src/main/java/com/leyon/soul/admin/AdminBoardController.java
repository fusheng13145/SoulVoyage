package com.leyon.soul.admin;

import com.leyon.soul.admin.dto.AdminDtos.BoardCheckIns;
import com.leyon.soul.admin.dto.AdminDtos.BoardDayValence;
import com.leyon.soul.admin.dto.AdminDtos.BoardDetailResp;
import com.leyon.soul.admin.dto.AdminDtos.BoardEmotion;
import com.leyon.soul.admin.dto.AdminDtos.BoardMemberView;
import com.leyon.soul.admin.dto.AdminDtos.BoardStatsResp;
import com.leyon.soul.admin.dto.AdminDtos.RemoveMemberResp;
import com.leyon.soul.audit.AuditService;
import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.domain.board.GroupBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * M12 辅导员群体看板管理端（手册 §11.3 V2.x 第二项）：
 * 群体与成员的维护是管理动作（辅导员本就知道自己带哪些学生）；
 * 心理数据的唯一出口是 stats，且只出聚合、受三重隐私闸约束（见 {@link GroupBoardService}），每次读取入审计链。
 */
@RestController
@RequestMapping("/api/v1/admin/board")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:board')")
public class AdminBoardController {

    public record GroupBody(String name) {}

    public record MembersBody(List<Long> userIds) {}

    private final GroupBoardService board;
    private final AuditService audit;

    @GetMapping("/groups")
    public ApiResponse<List<Map<String, Object>>> groups() {
        return ApiResponse.ok(board.listGroups());
    }

    @PostMapping("/groups")
    public ApiResponse<BoardDetailResp> create(@AuthenticationPrincipal AuthPrincipal p,
                                               @RequestBody GroupBody body) {
        Map<String, Object> g = board.createGroup(body.name());
        audit.record(p.userId(), "BOARD_GROUP_CREATE", "support_group:" + g.get("id"), null);
        return ApiResponse.ok(detailView(g));
    }

    @GetMapping("/groups/{id}")
    public ApiResponse<BoardDetailResp> detail(@PathVariable Long id) {
        return ApiResponse.ok(detailView(board.detail(id)));
    }

    @PostMapping("/groups/{id}/members")
    public ApiResponse<BoardDetailResp> addMembers(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id,
                                                   @RequestBody MembersBody body) {
        Map<String, Object> g = board.addMembers(id, body.userIds());
        audit.record(p.userId(), "BOARD_MEMBER_ADD", "support_group:" + id, null);
        return ApiResponse.ok(detailView(g));
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    public ApiResponse<RemoveMemberResp> removeMember(@AuthenticationPrincipal AuthPrincipal p,
                                                      @PathVariable Long id,
                                                      @PathVariable Long userId) {
        board.removeMember(id, userId);
        audit.record(p.userId(), "BOARD_MEMBER_REMOVE", "support_group:" + id + "+user:" + userId, null);
        return ApiResponse.ok(new RemoveMemberResp(id, userId, true));
    }

    /** 聚合统计唯一读口；授权人数未达阈值时只有抑制结论，无任何指标字段 */
    @GetMapping("/groups/{id}/stats")
    public ApiResponse<BoardStatsResp> stats(@AuthenticationPrincipal AuthPrincipal p,
                                             @PathVariable Long id,
                                             @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> resp = board.stats(id, days);
        audit.record(p.userId(), "ADMIN_VIEW_BOARD", "support_group:" + id
                + (Boolean.TRUE.equals(resp.get("suppressed")) ? "+suppressed" : ""), null);
        return ApiResponse.ok(statsView(resp));
    }

    @SuppressWarnings("unchecked")
    private static BoardDetailResp detailView(Map<String, Object> m) {
        List<BoardMemberView> members = ((List<Map<String, Object>>) m.get("members")).stream()
                .map(x -> new BoardMemberView(
                        ((Number) x.get("userId")).longValue(), (String) x.get("username"),
                        Boolean.TRUE.equals(x.get("consented"))))
                .toList();
        return new BoardDetailResp(
                ((Number) m.get("id")).longValue(), (String) m.get("name"),
                ((Number) m.get("status")).intValue(), ((Number) m.get("memberCount")).intValue(),
                ((Number) m.get("consentedCount")).intValue(), members);
    }

    @SuppressWarnings("unchecked")
    private static BoardStatsResp statsView(Map<String, Object> m) {
        BoardCheckIns checkIns = null;
        if (m.get("checkIns") instanceof Map<?, ?> ci) {
            Map<String, Object> c = (Map<String, Object>) ci;
            List<BoardEmotion> emotions = ((List<Map<String, Object>>) c.get("emotions")).stream()
                    .map(e -> new BoardEmotion((String) e.get("code"),
                            ((Number) e.get("count")).longValue()))
                    .toList();
            checkIns = new BoardCheckIns(
                    ((Number) c.get("contributors")).longValue(),
                    ((Number) c.get("personDays")).longValue(),
                    c.get("avgRating") == null ? null : ((Number) c.get("avgRating")).doubleValue(),
                    c.get("avgEnergy") == null ? null : ((Number) c.get("avgEnergy")).doubleValue(),
                    emotions);
        }
        List<BoardDayValence> days = m.get("valenceByDay") == null ? null
                : ((List<Map<String, Object>>) m.get("valenceByDay")).stream()
                .map(d -> new BoardDayValence((String) d.get("date"),
                        ((Number) d.get("avgValence")).doubleValue()))
                .toList();
        return new BoardStatsResp(
                ((Number) m.get("groupId")).longValue(), (String) m.get("groupName"),
                ((Number) m.get("windowDays")).intValue(), ((Number) m.get("memberCount")).intValue(),
                ((Number) m.get("consentedCount")).intValue(), ((Number) m.get("threshold")).intValue(),
                Boolean.TRUE.equals(m.get("suppressed")), (String) m.get("reason"), checkIns, days,
                m.get("hiddenDays") == null ? null : ((Number) m.get("hiddenDays")).intValue(),
                (Map<String, Long>) m.get("riskEvents"));
    }
}
