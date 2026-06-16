package com.agentum.system.infrastructure;

import com.agentum.system.application.McpConnectionTester;
import com.agentum.system.application.McpConnectionTestOutcome;
import com.agentum.system.application.McpConnectionTestRequest;
import com.agentum.system.application.McpSseConnectionTester;
import com.agentum.system.application.McpSseTestOutcome;
import com.agentum.system.application.McpSseTestRequest;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component("mcpConnectionTesterRouter")
public class McpConnectionTesterRouter implements McpConnectionTester {

    private final McpSseConnectionTester sseTester;
    private final McpConnectionTester streamableHttpTester;

    public McpConnectionTesterRouter(
        McpSseConnectionTester sseTester,
        @Qualifier("mcpStreamableHttpConnectionTester") McpConnectionTester streamableHttpTester
    ) {
        this.sseTester = sseTester;
        this.streamableHttpTester = streamableHttpTester;
    }

    @Override
    public McpConnectionTestOutcome test(McpConnectionTestRequest request) {
        String transport = request.transportType() == null ? "sse" : request.transportType().trim().toLowerCase();
        if ("streamable_http".equals(transport)) {
            return streamableHttpTester.test(request);
        } else {
            McpSseTestRequest sseRequest = new McpSseTestRequest(request.capabilityId(), request.endpointUrl());
            McpSseTestOutcome sseOutcome = sseTester.test(sseRequest);
            return convert(sseOutcome);
        }
    }

    private McpConnectionTestOutcome convert(McpSseTestOutcome sseOutcome) {
        List<McpConnectionTestOutcome.McpToolDescriptor> tools = sseOutcome.tools().stream()
            .map(t -> new McpConnectionTestOutcome.McpToolDescriptor(t.name(), t.description(), t.inputSchema()))
            .collect(Collectors.toList());
        return new McpConnectionTestOutcome(sseOutcome.status(), sseOutcome.summary(), tools);
    }
}
