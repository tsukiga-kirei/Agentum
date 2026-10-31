package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class StructuredContentTest {
    private TemplateModels.Field text() { return new TemplateModels.Field("summary", "总结", "markdown", true, 1000, null, "", List.of()); }
    @Test void parsesOnlyCompleteOuterFenceAndKeepsMarkdownInValue() {
        var data = StructuredContent.parse("```json\n{\"summary\":\"**内容**\\n\\n- 事项\"}\n```");
        StructuredContent.validate(data, List.of(text())); assertThat(data.get("summary")).isEqualTo("**内容**\n\n- 事项");
    }
    @Test void rejectsDuplicateKeysTrailingExplanationAndTruncatedJson() {
        for (var value : List.of("{\"a\":1,\"a\":2}", "说明：{\"a\":1}", "{\"a\":1} 后文", "{\"a\":", "```json\n{}")) assertThatThrownBy(() -> StructuredContent.parse(value)).hasMessageContaining("JSON");
    }
    @Test void rejectsMissingWrongTypeAndUnknownFields() {
        assertThatThrownBy(() -> StructuredContent.validate(Map.of(), List.of(text()))).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> StructuredContent.validate(Map.of("summary", 1), List.of(text()))).hasMessageContaining("必须是文字");
        assertThatThrownBy(() -> StructuredContent.validate(Map.of("summary", "内容", "extra", "其他"), List.of(text()))).hasMessageContaining("未声明");
    }
    @Test void rejectsHtmlAndMalformedMarkdownTable() {
        assertThatThrownBy(() -> StructuredContent.validate(Map.of("summary", "<script>bad</script>"), List.of(text()))).hasMessageContaining("HTML");
        assertThatThrownBy(() -> StructuredContent.validate(Map.of("summary", "| A | B |\n| --- | --- |\n| C |"), List.of(text()))).hasMessageContaining("列数");
    }
    @Test void allowsOptionalNullAndProtectsObjectPath() {
        var field = new TemplateModels.Field("summary", "总结", "text", false, null, null, "", List.of());
        Map<String, Object> data = new HashMap<>(); data.put("summary", null); StructuredContent.validate(data, List.of(field));
        assertThat(DeliveryTemplateService.path(Map.of("a", Map.of("b", 1)), "a.b")).isEqualTo(1);
        assertThatThrownBy(() -> DeliveryTemplateService.path(Map.of(), "a[0]")).hasMessageContaining("路径");
    }
}
