package com.soulvoyage.admin;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.board.GroupBoardService;
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
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal AuthPrincipal p,
                                                   @RequestBody GroupBody body) {
        Map<String, Object> g = board.createGroup(body.name());
        audit.record(p.userId(), "BOARD_GROUP_CREATE", "support_group:" + g.get("id"), null);
        return ApiResponse.ok(g);
    }

    @GetMapping("/groups/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(board.detail(id));
    }

    @PostMapping("/groups/{id}/members")
    public ApiResponse<Map<String, Object>> addMembers(@AuthenticationPrincipal AuthPrincipal p,
                                                      @PathVariable Long id,
                                                      @RequestBody MembersBody body) {
        Map<String, Object> g = board.addMembers(id, body.userIds());
        audit.record(p.userId(), "BOARD_MEMBER_ADD", "support_group:" + id, null);
        return ApiResponse.ok(g);
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    public ApiResponse<Map<String, Object>> removeMember(@AuthenticationPrincipal AuthPrincipal p,
                                                         @PathVariable Long id,
                                                         @PathVariable Long userId) {
        board.removeMember(id, userId);
        audit.record(p.userId(), "BOARD_MEMBER_REMOVE", "support_group:" + id + "+user:" + userId, null);
        return ApiResponse.ok(Map.of("groupId", id, "userId", userId, "removed", true));
    }

    /** 聚合统计唯一读口；授权人数未达阈值时只有抑制结论，无任何指标字段 */
    @GetMapping("/groups/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@AuthenticationPrincipal AuthPrincipal p,
                                                  @PathVariable Long id,
                                                  @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> resp = board.stats(id, days);
        audit.record(p.userId(), "ADMIN_VIEW_BOARD", "support_group:" + id
                + (Boolean.TRUE.equals(resp.get("suppressed")) ? "+suppressed" : ""), null);
        return ApiResponse.ok(resp);
    }
}
