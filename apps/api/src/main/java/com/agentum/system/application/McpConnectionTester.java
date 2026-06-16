package com.agentum.system.application;

/**
 * 统一的 MCP 连通性测试接口，支持不同的传输协议类型（如 SSE, Streamable HTTP 等）。
 */
public interface McpConnectionTester {

    McpConnectionTestOutcome test(McpConnectionTestRequest request);
}
