package com.agentum.workbench.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeVariableMergeTest {

    @Test
    void shouldNotOverwriteExistingValueWithBlankIncoming() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("report_month", "5");

        WorkflowRuntimeVariableMerge.mergeOutputs(variables, Map.of(
            "report_month", "",
            "financial_data_package", "package-ready"
        ));

        assertThat(variables).containsEntry("report_month", "5");
        assertThat(variables).containsEntry("financial_data_package", "package-ready");
    }

    @Test
    void shouldApplyScheduledInputFallbackWhenEarlierValueMissing() {
        Map<String, Object> variables = new LinkedHashMap<>();

        WorkflowRuntimeVariableMerge.mergeOutputs(variables, Map.of(
            "report_year", "2026",
            "report_month", "5"
        ));

        assertThat(variables).containsEntry("report_month", "5");
        assertThat(variables).containsEntry("report_year", "2026");
    }

    @Test
    void shouldAppendBusinessVariablesToClassifierPrompt() {
        String prompt = ClusterIntentRoutingSupport.enrichClassifierUserPrompt(
            "根据{{report_month}}判断月份。",
            Map.of("report_month", "5", "current_month_padded", "07", "summary", "已完成")
        );

        assertThat(prompt).contains("根据{{report_month}}判断月份。");
        assertThat(prompt).contains("上游变量快照");
        assertThat(prompt).contains("report_month: 5");
        assertThat(prompt).doesNotContain("current_month_padded: 07");
        assertThat(prompt).doesNotContain("summary:");
    }
}
