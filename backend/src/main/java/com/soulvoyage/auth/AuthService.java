package com.soulvoyage.auth;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.dto.AuthDtos.*;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;

    @Transactional
    public TokenResp register(RegisterReq req, String ip) {
        userRepo.findByUsernameAndDeletedAtIsNull(req.username()).ifPresent(u -> {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        });
        UserEntity u = new UserEntity();
        u.setUsername(req.username());
        u.setNickname(req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setAgreedPolicyAt(Instant.now());
        u = userRepo.save(u);
        audit.record(u.getId(), "REGISTER", "user:" + u.getId(), ip);
        return tokenResp(u);
    }

    public TokenResp login(LoginReq req, String ip) {
        UserEntity u = userRepo.findByUsernameAndDeletedAtIsNull(req.username())
                .filter(x -> encoder.matches(req.password(), x.getPasswordHash()))
                .filter(x -> x.getStatus() == 1)
                .orElseThrow(() -> new BizException(ErrorCode.LOGIN_FAILED));
        audit.record(u.getId(), "LOGIN", "user:" + u.getId(), ip);
        return tokenResp(u);
    }

    public TokenResp refresh(String refreshToken) {
        Claims c = jwt.verify(refreshToken, JwtService.TYPE_REFRESH);
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(Long.parseLong(c.getSubject()))
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        return tokenResp(u);
    }

    public void logout(String accessToken, String refreshToken) {
        jwt.revoke(accessToken);
        if (refreshToken != null && !refreshToken.isBlank()) jwt.revoke(refreshToken);
    }

    private TokenResp tokenResp(UserEntity u) {
        return new TokenResp(jwt.issueAccessToken(u.getId(), u.getRole()),
                jwt.issueRefreshToken(u.getId(), u.getRole()),
                jwt.accessTtl().toSeconds(),
                u.getId(), u.getNickname(), u.getRole());
    }
}
