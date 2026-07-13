package com.agentum.auth.application;

import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Basic 单点登录的浏览器交接码服务。
 *
 * <p>OA 等可信业务系统先用服务端 Basic 凭据换取短期、一次性的交接码；浏览器随后只访问 Agentum 域名消费该码，
 * 从而避免把共享密码、Access Token 或 Refresh Token 交给浏览器。</p>
 */
@Service
public class BasicSsoHandoffService {

    static final String KEY_PREFIX = "auth:sso:basic:handoff:";
    private static final int CODE_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;
    private final Duration ttl;

    @Autowired
    public BasicSsoHandoffService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${agentum.auth.basic-handoff-ttl-seconds:60}") long ttlSeconds
    ) {
        this(redisTemplate, objectMapper, new SecureRandom(), Duration.ofSeconds(ttlSeconds));
    }

    BasicSsoHandoffService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        SecureRandom random,
        Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.random = random;
        this.ttl = ttl;
    }

    public String create(BasicSsoHandoff handoff) {
        try {
            byte[] value = new byte[CODE_BYTES];
            random.nextBytes(value);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
            redisTemplate.opsForValue().set(key(code), objectMapper.writeValueAsString(handoff), ttl);
            return code;
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建企业 Basic 登录交接码", exception);
        }
    }

    public BasicSsoHandoff consume(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{40,}")) {
            throw invalidHandoff();
        }
        try {
            // Redis GETDEL 使同一交接码只能被一个浏览器请求成功消费，避免重放建立第二个会话。
            String serialized = redisTemplate.opsForValue().getAndDelete(key(code));
            if (serialized == null || serialized.isBlank()) {
                throw invalidHandoff();
            }
            return objectMapper.readValue(serialized, BasicSsoHandoff.class);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidHandoff();
        }
    }

    static String key(String code) {
        return KEY_PREFIX + code;
    }

    private static ApiException invalidHandoff() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_HANDOFF_INVALID", "企业单点登录地址无效或已过期，请从业务系统重新进入");
    }
}
