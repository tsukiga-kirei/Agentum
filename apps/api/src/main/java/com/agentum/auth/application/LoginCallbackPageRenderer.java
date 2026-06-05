package com.agentum.auth.application;

import com.agentum.auth.interfaces.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginCallbackPageRenderer {

    private final ObjectMapper objectMapper;
    private final String webBaseUrl;

    public LoginCallbackPageRenderer(
        ObjectMapper objectMapper,
        @Value("${agentum.auth.sso-web-base-url:http://localhost:5173}") String webBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.webBaseUrl = stripTrailingSlash(webBaseUrl);
    }

    public String render(LoginResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>企业 SSO 登录完成</title></head>
                <body>
                <script>
                  const payload = %s;
                  if (window.opener) {
                    window.opener.postMessage({ type: "agentum:sso-login", payload }, "%s");
                    window.close();
                  } else {
                    window.localStorage.setItem("agentum_sso_callback", JSON.stringify(payload));
                    window.location.replace("%s");
                  }
                </script>
                企业 SSO 登录完成，请返回 Agentum。
                </body>
                </html>
                """.formatted(payload, webBaseUrl, webBaseUrl);
        } catch (Exception exception) {
            throw new IllegalStateException("无法渲染企业 SSO 回调页面", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
