package com.agentum.auth.application;

import com.agentum.auth.interfaces.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
            String expectedOrigin = originOf(webBaseUrl);
            return """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>企业 SSO 登录完成</title></head>
                <body>
                <script>
                  const payload = %s;
                  const expectedOrigin = "%s";
                  const webBaseUrl = "%s";
                  // OA 菜单常用 window.open 打开 Agentum，opener 是跨域 OA 页。
                  // 只有 Agentum 登录页自己弹出的窗口才回传并关闭，避免 OA Basic 单点后秒关。
                  function openedByAgentumLogin() {
                    if (!window.opener) {
                      return false;
                    }
                    try {
                      return window.opener.location.origin === expectedOrigin;
                    } catch (error) {
                      return false;
                    }
                  }
                  if (openedByAgentumLogin()) {
                    window.opener.postMessage({ type: "agentum:sso-login", payload }, expectedOrigin);
                    window.close();
                  } else {
                    window.localStorage.setItem("agentum_sso_callback", JSON.stringify(payload));
                    window.location.replace(webBaseUrl);
                  }
                </script>
                企业 SSO 登录完成，请返回 Agentum。
                </body>
                </html>
                """.formatted(payload, expectedOrigin, webBaseUrl);
        } catch (Exception exception) {
            throw new IllegalStateException("无法渲染企业 SSO 回调页面", exception);
        }
    }

    private static String originOf(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return baseUrl;
            }
            int port = uri.getPort();
            if (port == -1) {
                return uri.getScheme() + "://" + uri.getHost();
            }
            return uri.getScheme() + "://" + uri.getHost() + ":" + port;
        } catch (IllegalArgumentException exception) {
            return baseUrl;
        }
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
