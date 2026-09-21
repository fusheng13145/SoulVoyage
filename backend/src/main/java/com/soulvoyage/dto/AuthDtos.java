package com.soulvoyage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record RegisterReq(
            @NotBlank @Size(min = 3, max = 32)
            @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "仅限字母数字下划线")
            String username,
            @NotBlank @Size(min = 8, max = 64) String password,
            @Size(max = 32) String nickname
    ) {}

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    public record RefreshReq(@NotBlank String refreshToken) {}

    public record DeleteReq(@NotBlank String password) {}

    public record PasswordReq(@NotBlank String oldPassword,
                              @NotBlank @Size(min = 8, max = 64) String newPassword) {}

    /** deletionPending=true 表示账号处于注销冷静期（S2），前端据此弹"撤回注销" */
    public record TokenResp(String accessToken, String refreshToken, long expiresIn,
                            long userId, String nickname, String role,
                            boolean deletionPending) {}

    // ---- M14 小程序端：微信只做"既有账号的第二个入口"，不另立账号体系（口令仍是真源） ----

    public record WxLoginReq(@NotBlank String code) {}

    public record WxBindReq(@NotBlank String bindTicket,
                            @NotBlank String username,
                            @NotBlank String password) {}

    /**
     * 小程序端注册：用户名与口令的规则逐字照抄 RegisterReq，而不是"到了另一个端就松一点"——
     * 弱口令在 Web 上挡住、在小程序上放行，等于把最弱的那个入口当成全站口径。
     */
    public record WxRegisterReq(@NotBlank String bindTicket,
                                @NotBlank @Size(min = 3, max = 32)
                                @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "仅限字母数字下划线")
                                String username,
                                @NotBlank @Size(min = 8, max = 64) String password,
                                @Size(max = 32) String nickname
    ) {}

    /**
     * 微信登录回执，两种形态二选一：
     * bound=true 时 token 有值（可直接进主界面）；bound=false 时只有 bindTicket（引导去绑定或注册）。
     * 未绑定的账号绝不发半吊子令牌——小程序看到的永远只是那张一次性票。
     */
    public record WxLoginResp(boolean bound, String bindTicket, Long ticketExpiresInSeconds,
                              TokenResp token) {}
}
