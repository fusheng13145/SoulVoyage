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

    public record TokenResp(String accessToken, String refreshToken, long expiresIn,
                            long userId, String nickname, String role) {}
}
