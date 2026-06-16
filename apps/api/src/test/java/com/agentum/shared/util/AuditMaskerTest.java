package com.agentum.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditMaskerTest {

    @Test
    void shouldMaskJsonByKeyAndValue() {
        String rawJson = "{\"username\":\"designer\",\"password\":\"agentum123\",\"info\":{\"secret_token\":\"abc123xyz\",\"hobby\":\"coding\"},\"unrelated\":\"hello\"}";
        Set<String> sensitiveKeys = Set.of("secret_token");
        Set<String> sensitiveValues = Set.of("coding");

        String masked = AuditMasker.maskJson(rawJson, sensitiveKeys, sensitiveValues);

        // 1. password 虽不在 sensitiveKeys 中，但触发内置模糊键匹配，应脱敏
        assertThat(masked).contains("\"password\":\"******\"");
        // 2. secret_token 明确在 sensitiveKeys 中，应脱敏
        assertThat(masked).contains("\"secret_token\":\"******\"");
        // 3. coding 作为敏感值被匹配，应脱敏
        assertThat(masked).contains("\"hobby\":\"******\"");
        // 4. unrelated 无敏感信息，保持原样
        assertThat(masked).contains("\"unrelated\":\"hello\"");
    }

    @Test
    void shouldMaskArrayInJson() {
        String rawJson = "{\"items\":[\"normal\",\"coding\",\"secret_val\"]}";
        Set<String> sensitiveValues = Set.of("coding");

        String masked = AuditMasker.maskJson(rawJson, null, sensitiveValues);
        assertThat(masked).contains("\"normal\"");
        assertThat(masked).contains("\"******\"");
    }

    @Test
    void shouldReturnErrorJsonOnInvalidJson() {
        String invalidJson = "{invalid}";
        String masked = AuditMasker.maskJson(invalidJson, null, null);
        assertThat(masked).contains("error");
    }

    @Test
    void shouldMaskTextCredentialAndPlainValues() {
        String text = "Please connect to OpenAI API using key sk-abcdefghijklmnopqrstuvwxyz0123456789ABCDEF123456 or Authorization: Bearer eyJhbGciOi. Also, the user's password is secretPassword123.";
        Set<String> sensitiveValues = Set.of("secretPassword123");

        String masked = AuditMasker.maskText(text, sensitiveValues);

        // 1. sk- 凭证格式自动遮掩
        assertThat(masked).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz0123456789ABCDEF123456");
        // 2. Bearer token 格式自动遮掩
        assertThat(masked).doesNotContain("Bearer eyJhbGciOi. Also");
        // 3. 上下文敏感值密码被脱敏
        assertThat(masked).doesNotContain("secretPassword123");
        assertThat(masked).contains("password is ******");
    }

    @Test
    void shouldNotMaskShortValuesInText() {
        String text = "The status is yes, 1 and ok.";
        Set<String> sensitiveValues = Set.of("yes", "1", "ok");

        String masked = AuditMasker.maskText(text, sensitiveValues);
        // 长度小于等于 3，不应被脱敏，防止大面积误伤
        assertThat(masked).isEqualTo(text);
    }
}
