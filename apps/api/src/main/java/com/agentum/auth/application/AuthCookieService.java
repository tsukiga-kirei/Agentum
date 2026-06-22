package com.agentum.auth.application;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookieService {

    public static final String REFRESH_COOKIE = "agentum_refresh_token";
    private final boolean secure;

    public AuthCookieService(@Value("${agentum.auth.refresh-cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public String create(IssuedRefreshToken token) {
        long maxAge = Math.max(0, Duration.between(java.time.Instant.now(), token.expiresAt()).toSeconds());
        return cookie(token.value(), maxAge).toString();
    }

    public String clear() {
        return cookie("", 0).toString();
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(cookie -> REFRESH_COOKIE.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api/auth")
            .maxAge(maxAge)
            .build();
    }
}
