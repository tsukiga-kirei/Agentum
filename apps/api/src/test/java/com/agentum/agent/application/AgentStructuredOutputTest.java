package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.*;
import com.agentum.delivery.template.TemplateModels;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentStructuredOutputTest {
    private Map<String, Object> config() {
        return Map.of("output", "report_text", "structuredOutput", Map.of("enabled", true, "variable", "report_data", "fields", List.of(
            new TemplateModels.Field("summary", "总结", "text", true, 100, null, "", List.of()),
            new TemplateModels.Field("amount", "金额", "number", true, null, null, "", List.of())
        )));
    }
    @Test void oneAgentStoresSeveralFieldsWithoutReplacingItsTextOutput() {
        Map<String, Object> output = new LinkedHashMap<>(); output.put("report_text", "可阅读报告"); output.put("final_answer", "可阅读报告");
        output.put("chatMessages", List.of(new LinkedHashMap<>(Map.of("role", "assistant", "content", "可阅读报告"))));
        var data = AgentStructuredOutput.validate(config(), Map.of("summary", "总结", "amount", 12));
        AgentStructuredOutput.attach(config(), output, data);
        assertThat(output).containsEntry("report_text", "可阅读报告").containsEntry("report_data", data);
        assertThat(((Map<?, ?>) ((List<?>) output.get("chatMessages")).getFirst()).get("structuredData")).isEqualTo(data);
    }
    @Test void followUpKeepsFormalDataUntilAnExplicitResultIsAdopted() {
        Map<String, Object> config = new LinkedHashMap<>(config());
        var formal = Map.of("summary", "旧总结", "amount", 10);
        config.put("_formalAnswer", "旧报告"); config.put("_formalStructuredData", formal);
        Map<String, Object> output = new LinkedHashMap<>();
        var next = Map.<String, Object>of("summary", "新总结", "amount", 20);
        output.put("chatMessages", List.of(new LinkedHashMap<>(Map.of("role", "assistant", "content", "新报告"))));
        AgentStructuredOutput.attach(config, output, next);
        assertThat(output.get("report_data")).isEqualTo(formal);
        output.put("selectedResultMessageIndex", 0);
        AgentStructuredOutput.adoptForAnswer(config, output, "新报告");
        assertThat(output.get("report_data")).isEqualTo(next);
        assertThat(output).doesNotContainKey("candidateStructuredData");
    }
    @Test void resultSelectionUsesTheExactMessageEvenWhenDisplayTextIsIdentical() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chatMessages", List.of(Map.of("content", "相同说明", "structuredData", Map.of("summary", "A", "amount", 1)), Map.of("content", "相同说明", "structuredData", Map.of("summary", "B", "amount", 2))));
        output.put("selectedResultMessageIndex", 0);
        AgentStructuredOutput.adoptForAnswer(config(), output, "相同说明");
        assertThat(((Map<?, ?>) output.get("report_data")).get("summary")).isEqualTo("A");
    }
    @Test void rejectsWrongTypesAndUnstructuredManualEdits() {
        assertThatThrownBy(() -> AgentStructuredOutput.validate(config(), Map.of("summary", "结论", "amount", "12"))).hasMessageContaining("数字");
        assertThatThrownBy(() -> AgentStructuredOutput.adoptForAnswer(config(), new HashMap<>(), "只修改文字")).hasMessageContaining("JSON");
    }
    @Test void disabledModeDoesNotAlterExistingOutputs() {
        Map<String, Object> output = new HashMap<>(Map.of("final_answer", "旧行为"));
        AgentStructuredOutput.attach(Map.of(), output, Map.of("any", "value")); assertThat(output).containsOnlyKeys("final_answer");
    }
    @Test void manualFieldEditsCreateReadableResultAndKeepTheNewDataInChatHistory() {
        Map<String, Object> outputs = new LinkedHashMap<>();
        String json = "{\"summary\":\"人工核实后的总结\",\"amount\":200}";
        AgentStructuredOutput.adoptForAnswer(config(), outputs, json);
        String display = AgentStructuredOutput.readableEditedAnswer(config(), outputs, json);
        assertThat(display).contains("### 总结", "人工核实后的总结", "### 金额", "200");
        assertThat(((Map<?, ?>) ((List<?>) outputs.get("chatMessages")).getFirst()).get("structuredData")).isEqualTo(outputs.get("report_data"));
    }
}
