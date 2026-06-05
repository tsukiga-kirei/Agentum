package com.agentum.auth.application;

import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
import org.springframework.stereotype.Service;

@Service
public class SsoStateService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Clock clock;
    private final String secret;
    private final Duration ttl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SsoStateService(
        @Value("${agentum.auth.sso-state-secret:${agentum.auth.token-secret:agentum-local-sso-state-secret}}") String secret,
        @Value("${agentum.auth.sso-state-ttl-minutes:5}") long ttlMinutes
    ) {
        this(Clock.systemUTC(), secret, Duration.ofMinutes(ttlMinutes));
    }

    SsoStateService(Clock clock, String secret, Duration ttl) {
        this.clock = clock;
        this.secret = secret;
        this.ttl = ttl;
    }

    public String createState(UUID tenantId, UUID providerId, String portal) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("providerId", providerId.toString());
        payload.put("portal", portal);
        payload.put("nonce", randomNonce());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String encodedPayload = encode(writeJson(payload));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public SsoState parseState(String state) {
        try {
            String[] parts = state == null ? new String[0] : state.split("\\.");
            if (parts.length != 2) {
                throw invalidState();
            }
            String expected = sign(parts[0]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
                throw invalidState();
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            Instant issuedAt = Instant.ofEpochSecond(payload.path("iat").asLong());
            Instant expiresAt = Instant.ofEpochSecond(payload.path("exp").asLong());
            if (!expiresAt.isAfter(clock.instant())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_STATE_EXPIRED", "企业 SSO 登录状态已过期，请重新发起登录");
            }
            return new SsoState(
                UUID.fromString(payload.path("tenantId").asText()),
                UUID.fromString(payload.path("providerId").asText()),
                payload.path("portal").asText(),
                payload.path("nonce").asText(),
                issuedAt,
                expiresAt
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidState();
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成企业 SSO 登录状态", exception);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return encode(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("无法生成企业 SSO 状态签名", exception);
        }
    }

    private static String randomNonce() {
        byte[] bytes = new byte[18];
        SECURE_RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static ApiException invalidState() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_STATE_INVALID", "企业 SSO 登录状态无效，请重新发起登录");
    }
}
