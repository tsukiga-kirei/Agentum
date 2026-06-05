package com.agentum.auth.infrastructure;

import com.agentum.auth.application.OidcExternalIdentity;
import com.agentum.auth.application.OidcIdentityClient;
import com.agentum.auth.domain.TenantSsoProviderEntity;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class HttpOidcIdentityClient implements OidcIdentityClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOidcIdentityClient.class);

    private final FieldEncryptionService fieldEncryptionService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpOidcIdentityClient(FieldEncryptionService fieldEncryptionService, ObjectMapper objectMapper) {
        this.fieldEncryptionService = fieldEncryptionService;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public OidcExternalIdentity exchangeCode(TenantSsoProviderEntity provider, String code, String redirectUri, String expectedNonce) {
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", redirectUri);
            form.add("client_id", provider.getClientId());
            String clientSecret = fieldEncryptionService.decrypt(provider.getEncryptedClientSecret());
            if (clientSecret != null && !clientSecret.isBlank()) {
                form.add("client_secret", clientSecret);
            }

            String response = restClient.post()
                .uri(provider.getTokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
            JsonNode tokenPayload = objectMapper.readTree(response);
            String idToken = tokenPayload.path("id_token").asText();
            if (idToken.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_ID_TOKEN_MISSING", "企业 SSO 未返回身份令牌");
            }

            JwtDecoder decoder = JwtDecoders.fromIssuerLocation(provider.getIssuer());
            Jwt jwt = decoder.decode(idToken);
            validateClaims(provider, jwt, expectedNonce);
            return new OidcExternalIdentity(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("preferred_username"), jwt.getSubject())
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("企业 SSO 身份令牌交换失败 providerId={} requestId={}", provider.getId(), RequestIds.current());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_TOKEN_EXCHANGE_FAILED", "企业 SSO 登录失败，请联系管理员检查身份源配置");
        }
    }

    private static void validateClaims(TenantSsoProviderEntity provider, Jwt jwt, String expectedNonce) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_SUBJECT_MISSING", "企业 SSO 未返回稳定用户标识");
        }
        String nonce = jwt.getClaimAsString("nonce");
        if (expectedNonce == null || !expectedNonce.equals(nonce)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_NONCE_INVALID", "企业 SSO 登录校验失败，请重新登录");
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(provider.getClientId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SSO_AUDIENCE_INVALID", "企业 SSO 身份令牌受众不匹配");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
