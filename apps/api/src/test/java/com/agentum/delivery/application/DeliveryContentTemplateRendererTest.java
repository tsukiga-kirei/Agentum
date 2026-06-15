package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryContentTemplateRendererTest {

    private final DeliveryContentTemplateRenderer renderer = new DeliveryContentTemplateRenderer();

    @Test
    void shouldRenderVariablePlaceholdersAndExtractAgentAnswer() {
        String rendered = renderer.render(
            "# 结论\n\n{{risk_summary}}",
            Map.of("risk_summary", Map.of("final_answer", "授信通过"))
        );

        assertThat(rendered).isEqualTo("# 结论\n\n授信通过");
    }
}
