package com.leyon.soul.account;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.domain.archive.ArchiveService;
import com.leyon.soul.domain.archive.dto.ArchiveDtos.ExportTicketResp;
import com.leyon.soul.dto.AuthDtos.DeleteReq;
import com.leyon.soul.dto.AuthDtos.PasswordReq;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 下篇·S2 隐私中心端点：注销/撤回、改密、个人数据导出（GET 领取限时链接 → POST 一次性领取明文 JSON） */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService account;
    private final ArchiveService archive;

    @PostMapping("/delete")
    public ApiResponse<Void> requestDeletion(@AuthenticationPrincipal AuthPrincipal p,
                                              @Valid @RequestBody DeleteReq req,
                                              HttpServletRequest http) {
        account.requestDeletion(p.userId(), req.password(), http.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @PostMapping("/delete/cancel")
    public ApiResponse<Void> cancelDeletion(@AuthenticationPrincipal AuthPrincipal p,
                                             HttpServletRequest http) {
        account.cancelDeletion(p.userId(), http.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal AuthPrincipal p,
                                             @Valid @RequestBody PasswordReq req,
                                             HttpServletRequest http) {
        account.changePassword(p.userId(), req.oldPassword(), req.newPassword(), http.getRemoteAddr());
        return ApiResponse.ok(null);
    }

    @GetMapping("/data-export")
    public ApiResponse<ExportTicketResp> createDataExport(@AuthenticationPrincipal AuthPrincipal p,
                                                          HttpServletRequest http) {
        String fileId = archive.createDataExport(p.userId(), http.getRemoteAddr());
        return ApiResponse.ok(new ExportTicketResp(
                fileId, 300, null, "链接限时 5 分钟且仅可领取一次"));
    }

    @PostMapping("/data-export/{fileId}")
    public ApiResponse<ObjectNode> consumeDataExport(@AuthenticationPrincipal AuthPrincipal p,
                                                      @PathVariable String fileId,
                                                      HttpServletRequest http) {
        return ApiResponse.ok(archive.downloadExport(p.userId(), fileId, http.getRemoteAddr()));
    }
}
