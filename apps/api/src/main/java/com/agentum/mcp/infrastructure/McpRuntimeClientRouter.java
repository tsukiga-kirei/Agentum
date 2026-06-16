package com.agentum.mcp.infrastructure;

import com.agentum.mcp.application.McpRuntimeClient;
import com.agentum.mcp.domain.McpTransportType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component("mcpRuntimeClientRouter")
public class McpRuntimeClientRouter implements McpRuntimeClient {

    private final McpRuntimeClient sseClient;
    private final McpRuntimeClient streamableHttpClient;

    public McpRuntimeClientRouter(
        @Qualifier("mcpSseRuntimeClient") McpRuntimeClient sseClient,
        @Qualifier("mcpStreamableHttpRuntimeClient") McpRuntimeClient streamableHttpClient
    ) {
        this.sseClient = sseClient;
        this.streamableHttpClient = streamableHttpClient;
    }

    @Override
    public ToolResult callTool(ToolCall call) {
        McpTransportType transportType = McpTransportType.fromValue(call.transportType());
        if (transportType == McpTransportType.STREAMABLE_HTTP) {
            return streamableHttpClient.callTool(call);
        } else {
            return sseClient.callTool(call);
        }
    }
}
