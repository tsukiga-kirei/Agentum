package com.agentum.config;

import com.agentum.auth.application.AuthTokenClaims;
import com.agentum.auth.application.AuthTokenService;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiError;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);

    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;

    public BearerTokenAuthenticationFilter(AuthTokenService authTokenService, ObjectMapper objectMapper) {
        this.authTokenService = authTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String token = resolveBearerToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthTokenClaims claims = authTokenService.parse(token);
            CurrentUserPrincipal principal = new CurrentUserPrincipal(
                claims.userId(),
                claims.username(),
                claims.tenantId(),
                claims.role(),
                claims.portal(),
                claims.roleAssignmentId()
            );
            // 过滤器只负责把 Bearer Token 还原为 Principal；具体租户、资源和敏感动作权限仍由业务层重新校验。
            log.debug(
                "Bearer Token 解析成功 path={} userId={} tenantId={} role={} portal={} requestId={}",
                request.getRequestURI(),
                claims.userId(),
                claims.tenantId(),
                claims.role(),
                claims.portal(),
                RequestIds.current(request)
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                token,
                List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().toUpperCase()))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            log.warn(
                "Bearer Token 校验失败 path={} code={} requestId={}",
                request.getRequestURI(),
                exception.getCode(),
                RequestIds.current(request)
            );
            response.setStatus(exception.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = new ApiError(exception.getCode(), exception.getMessage(), exception.getDetails());
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error, RequestIds.current(request)));
        }
    }

    private static String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }

        return null;
    }
}
