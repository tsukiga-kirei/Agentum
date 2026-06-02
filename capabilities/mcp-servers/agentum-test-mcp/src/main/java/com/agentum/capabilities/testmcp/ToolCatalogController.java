package com.agentum.capabilities.testmcp;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ToolCatalogController {

    @GetMapping("/agentum/tools")
    public Map<String, Object> tools() {
        return Map.of(
            "server", "agentum-test-mcp",
            "tools", List.of(
                Map.of(
                    "name", "agentum.health_check",
                    "description", "返回测试 MCP 服务状态，用于系统管理连通性验证",
                    "inputSchema", Map.of("type", "object", "properties", Map.of())
                ),
                Map.of(
                    "name", "agentum.echo_context",
                    "description", "回显一段业务上下文，验证参数传入和工具返回格式",
                    "inputSchema", Map.of(
                        "type", "object",
                        "required", List.of("text"),
                        "properties", Map.of(
                            "text", Map.of("type", "string", "description", "业务上下文或测试文本"),
                            "caller", Map.of("type", "string", "description", "可选调用方标识")
                        )
                    )
                )
            )
        );
    }
}
