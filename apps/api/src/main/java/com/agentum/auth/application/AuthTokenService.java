package com.agentum.auth.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String tokenSecret;
    private final Duration tokenTtl;

    @Autowired
    public AuthTokenService(
        ObjectMapper objectMapper,
        @Value("${agentum.auth.token-secret:agentum-local-development-secret-change-before-production}") String tokenSecret,
        @Value("${agentum.auth.token-ttl-minutes:480}") long tokenTtlMinutes
    ) {
        this(objectMapper, Clock.systemUTC(), tokenSecret, Duration.ofMinutes(tokenTtlMinutes));
    }

    public AuthTokenService(ObjectMapper objectMapper, Clock clock, String tokenSecret, Duration tokenTtl) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tokenSecret = tokenSecret;
        this.tokenTtl = tokenTtl;
    }

    public String createToken(CurrentUserPrincipal principal) {
        // Token 只承载必要身份上下文，日志和异常都不能输出 token 原文。
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(tokenTtl);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", principal.userId().toString());
        payload.put("username", principal.username());
        payload.put("tenantId", principal.tenantId() == null ? null : principal.tenantId().toString());
        payload.put("role", principal.role());
        payload.put("portal", principal.portal());
        payload.put("raId", principal.roleAssignmentId() == null ? null : principal.roleAssignmentId().toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String unsignedToken = encodeJson(header) + "." + encodeJson(payload);
        return unsignedToken + "." + sign(unsignedToken);
    }

    public AuthTokenClaims parse(String token) {
        try {
            String[] parts = token.split("\\.");

            if (parts.length != 3) {
                throw invalidToken();
            }

            String unsignedToken = parts[0] + "." + parts[1];
            String expectedSignature = sign(unsignedToken);

            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                throw invalidToken();
            }

            JsonNode payload = objectMapper.readTree(decode(parts[1]));
            Instant issuedAt = Instant.ofEpochSecond(payload.path("iat").asLong());
            Instant expiresAt = Instant.ofEpochSecond(payload.path("exp").asLong());
            JsonNode tenantIdNode = payload.get("tenantId");
            UUID tenantId = tenantIdNode == null || tenantIdNode.isNull() ? null : UUID.fromString(tenantIdNode.asText());
            JsonNode raIdNode = payload.get("raId");
            UUID roleAssignmentId = raIdNode == null || raIdNode.isNull() ? null : UUID.fromString(raIdNode.asText());

            if (!expiresAt.isAfter(clock.instant())) {
                log.warn("Token 已过期 userId={} tenantId={} role={} requestId={}", payload.path("sub").asText(), tenantId, payload.path("role").asText(), RequestIds.current());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "登录状态已过期，请重新登录");
            }

            return new AuthTokenClaims(
                UUID.fromString(payload.path("sub").asText()),
                payload.path("username").asText(),
                tenantId,
                payload.path("role").asText(),
                payload.path("portal").asText(),
                roleAssignmentId,
                issuedAt,
                expiresAt
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return encode(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成登录令牌", exception);
        }
    }

    private String sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return encode(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("无法生成登录令牌签名", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "登录状态无效，请重新登录");
    }
}
