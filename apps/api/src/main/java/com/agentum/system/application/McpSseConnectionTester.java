package com.agentum.system.application;

/**
 * MCP SSE 连通性测试入口。系统管理页通过标准 MCP 协议（initialize + tools/list）验证底层服务是否可用。
 */
public interface McpSseConnectionTester {

    McpSseTestOutcome test(McpSseTestRequest request);
}
