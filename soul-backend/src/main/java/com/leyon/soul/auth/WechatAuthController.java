package com.leyon.soul.auth;

import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.util.ClientIpResolver;
import com.leyon.soul.dto.AuthDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 小程序端登录入口（M14）。前三个端点匿名可达（登录本来就匿名），
 * 解绑需要登录态——收回第三方入口这件事不能由一个未验证的请求替用户做。
 */
@RestController
@RequestMapping("/api/v1/auth/wechat")
@RequiredArgsConstructor
public class WechatAuthController {

    private final WechatAuthService service;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/login")
    public ApiResponse<WxLoginResp> login(@Valid @RequestBody WxLoginReq req, HttpServletRequest http) {
        return ApiResponse.ok(service.login(req, ip(http)));
    }

    @PostMapping("/bind")
    public ApiResponse<TokenResp> bind(@Valid @RequestBody WxBindReq req, HttpServletRequest http) {
        return ApiResponse.ok(service.bind(req, ip(http)));
    }

    @PostMapping("/register")
    public ApiResponse<TokenResp> register(@Valid @RequestBody WxRegisterReq req, HttpServletRequest http) {
        return ApiResponse.ok(service.register(req, ip(http)));
    }

    @DeleteMapping("/binding")
    public ApiResponse<Void> unbind(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest http) {
        service.unbind(p.userId(), ip(http));
        return ApiResponse.ok(null);
    }

    /** 与 AuthController 共用同一套客户端 IP 解析：只有内网来源才采信 X-Real-IP / XFF 末段 */
    private String ip(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }
}
