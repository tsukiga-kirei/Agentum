package com.agentum.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.auth.interfaces.AuthUserResponse;
import com.agentum.auth.interfaces.LoginResponse;
import com.agentum.auth.interfaces.MenuItemResponse;
import com.agentum.auth.interfaces.RoleInfoResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginCallbackPageRendererTest {

    @Test
    void shouldCloseOnlyWhenOpenerIsAgentumOrigin() {
        LoginCallbackPageRenderer renderer = new LoginCallbackPageRenderer(
            new ObjectMapper(),
            "http://10.129.7.243:84"
        );

        String html = renderer.render(loginResponse());

        assertThat(html)
            .contains("const expectedOrigin = \"http://10.129.7.243:84\";")
            .contains("const webBaseUrl = \"http://10.129.7.243:84\";")
            .contains("window.opener.location.origin === expectedOrigin")
            .contains("window.location.replace(webBaseUrl)")
            .doesNotContain("if (window.opener) {");
    }

    @Test
    void shouldUseOriginWithoutPathForPostMessageTarget() {
        LoginCallbackPageRenderer renderer = new LoginCallbackPageRenderer(
            new ObjectMapper(),
            "http://10.129.7.243:84/app/"
        );

        String html = renderer.render(loginResponse());

        assertThat(html)
            .contains("const expectedOrigin = \"http://10.129.7.243:84\";")
            .contains("const webBaseUrl = \"http://10.129.7.243:84/app\";");
    }

    private static LoginResponse loginResponse() {
        RoleInfoResponse role = new RoleInfoResponse(
            "role-1",
            "business",
            "tenant-1",
            "GJCW",
            "业务用户"
        );
        return new LoginResponse(
            "access-token",
            new AuthUserResponse(
                "user-1",
                "operator",
                "业务员",
                "operator@example.com",
                "",
                "business",
                "tenant-1",
                "GJCW",
                "GJCW",
                "总部",
                "2026-08-20T02:00:00Z"
            ),
            List.of(role),
            role,
            List.of(),
            List.of(new MenuItemResponse("workbench", "工作台", "layout", "业务工作台"))
        );
    }
}
