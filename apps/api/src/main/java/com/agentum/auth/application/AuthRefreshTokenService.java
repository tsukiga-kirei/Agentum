package com.agentum.auth.application;

import com.agentum.auth.domain.AuthRefreshTokenEntity;
import com.agentum.auth.infrastructure.AuthRefreshTokenRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthRefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthRefreshTokenService.class);
    private final AuthRefreshTokenRepository repository;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public AuthRefreshTokenService(
        AuthRefreshTokenRepository repository,
        @Value("${agentum.auth.refresh-token-ttl-days:30}") long ttlDays
    ) {
        this(repository, new SecureRandom(), Clock.systemUTC(), Duration.ofDays(ttlDays));
    }

    AuthRefreshTokenService(AuthRefreshTokenRepository repository, SecureRandom secureRandom, Clock clock, Duration ttl) {
        this.repository = repository;
        this.secureRandom = secureRandom;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public IssuedRefreshToken issue(UUID userId, UUID roleAssignmentId) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(ttl);
        repository.save(AuthRefreshTokenEntity.create(userId, roleAssignmentId, hash(rawToken), now, expiresAt));
        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    @Transactional
    public RotatedRefreshToken rotate(String rawToken) {
        Instant now = clock.instant();
        AuthRefreshTokenEntity stored = repository.findByTokenHash(hash(rawToken))
            .orElseThrow(AuthRefreshTokenService::invalidRefreshToken);

        if (stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            log.warn("Refresh Token 已失效 userId={} roleAssignmentId={} requestId={}", stored.getUserId(), stored.getRoleAssignmentId(), RequestIds.current());
            throw invalidRefreshToken();
        }

        // 先吊销旧令牌再签发新令牌，令牌只能成功使用一次；悲观锁避免并发重复刷新。
        stored.revoke(now);
        IssuedRefreshToken replacement = issue(stored.getUserId(), stored.getRoleAssignmentId());
        return new RotatedRefreshToken(stored.getUserId(), stored.getRoleAssignmentId(), replacement);
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) token.revoke(clock.instant());
        });
    }

    private static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成刷新令牌摘要", exception);
        }
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID", "登录状态已失效，请重新登录");
    }
}
