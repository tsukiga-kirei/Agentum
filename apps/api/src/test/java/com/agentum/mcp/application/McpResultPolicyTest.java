package com.agentum.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpResultPolicyTest {

    @Test
    @DisplayName("结果名称包含中文时应明确提示格式要求，不能使用模糊的重名错误")
    void shouldRejectChineseCharactersInDatasetKey() {
        Map<String, Object> policy = Map.of(
            "get_financial_work_report_interbank_details",
            Map.of(
                "mode", "dataset",
                "datasetKey", "get_financial_work_report_interbank_details啊"
            )
        );

        assertThatThrownBy(() -> McpResultPolicy.validatePolicies(policy))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("同一 MCP 的结果名称须为有效标识符");
    }

    @Test
    @DisplayName("同一 MCP 下结果名称重复时应提示重复错误")
    void shouldRejectDuplicateDatasetKey() {
        Map<String, Object> policy = Map.of(
            "tool_1", Map.of("mode", "dataset", "datasetKey", "same_key"),
            "tool_2", Map.of("mode", "dataset", "datasetKey", "same_key")
        );

        assertThatThrownBy(() -> McpResultPolicy.validatePolicies(policy))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("同一 MCP 的结果名称不能重复：same_key");
    }

    @Test
    @DisplayName("有效英文字母数字下划线结果名称应正常通过校验")
    void shouldAcceptValidDatasetKey() {
        Map<String, Object> policy = Map.of(
            "get_interbank", Map.of(
                "mode", "dataset",
                "datasetKey", "interbank_details",
                "rowsPath", "$.data.rows"
            )
        );

        Map<String, Object> validated = McpResultPolicy.validatePolicies(policy);
        assertThat(validated).containsKey("get_interbank");
    }
}
