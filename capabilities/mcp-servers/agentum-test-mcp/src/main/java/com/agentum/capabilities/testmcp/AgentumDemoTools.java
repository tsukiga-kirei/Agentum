package com.agentum.capabilities.testmcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentumDemoTools {

    @Tool(name = "agentum.health_check", description = "返回测试 MCP 服务状态，用于系统管理连通性验证")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("service", "agentum-test-mcp");
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    @Tool(name = "agentum.echo_context", description = "回显一段业务上下文，验证参数传入和工具返回格式")
    public Map<String, Object> echoContext(
        @ToolParam(description = "业务上下文或测试文本", required = true) String text,
        @ToolParam(description = "可选调用方标识", required = false) String caller
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text);
        result.put("caller", caller == null || caller.isBlank() ? "anonymous" : caller);
        result.put("length", text == null ? 0 : text.length());
        return result;
    }
}
