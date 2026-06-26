package com.agentum.config;

import com.agentum.shared.api.ApiError;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter) throws Exception {
        // 认证接口和健康检查公开，其余 API 统一走无状态 Bearer Token；前端入口隐藏不作为安全边界。
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                // SSE / SseEmitter 完成时会触发 ASYNC dispatch；此时响应已提交，不应再次鉴权。
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/tenants").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/tenants/*/sso-providers").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/bootstrap-status").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/bootstrap").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/sso/basic-entry").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/sso/authorize").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/sso/callback/*").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint((request, response, exception) -> {
                    if (response.isCommitted()) {
                        log.debug(
                            "未认证访问但响应已提交，跳过写入 path={} requestId={}",
                            request.getRequestURI(),
                            RequestIds.current(request)
                        );
                        return;
                    }
                    writeSecurityFailure(request, response, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录后再访问");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    if (response.isCommitted()) {
                        log.debug(
                            "访问被拒绝但响应已提交，跳过写入 path={} requestId={}",
                            request.getRequestURI(),
                            RequestIds.current(request)
                        );
                        return;
                    }
                    writeSecurityFailure(request, response, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有访问权限");
                })
            )
            .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // 当前 CORS 策略只服务本地前后端分端口调试，生产部署应由网关或域名白名单收敛。
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private void writeSecurityFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        HttpStatus status,
        String code,
        String message
    ) throws IOException {
        log.warn("安全访问被拒绝 path={} code={} status={} requestId={}", request.getRequestURI(), code, status.value(), RequestIds.current(request));
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = new ApiError(code, message);
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error, RequestIds.current(request)));
    }
}
