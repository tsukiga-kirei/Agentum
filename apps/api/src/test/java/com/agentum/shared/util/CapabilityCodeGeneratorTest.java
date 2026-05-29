package com.agentum.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CapabilityCodeGeneratorTest {

    @Test
    void shouldSlugEnglishNameToCode() {
        assertThat(CapabilityCodeGenerator.slugFromName("Renewal Question")).isEqualTo("renewal_question");
        assertThat(CapabilityCodeGenerator.slugFromName("doc-parser")).isEqualTo("doc_parser");
    }

    @Test
    void shouldFallbackToHashForNonLatinName() {
        String code = CapabilityCodeGenerator.slugFromName("续约追问模板");
        assertThat(code).startsWith("cap_");
        assertThat(code).matches("[a-z][a-z0-9_\\-]{1,99}");
    }

    @Test
    void shouldResolveUniqueCodeWithSuffix() {
        String code = CapabilityCodeGenerator.resolveUniqueCode(
            "Renewal Question",
            "v1",
            (candidate, version) -> "renewal_question".equals(candidate) && "v1".equals(version)
        );
        assertThat(code).isEqualTo("renewal_question_2");
    }
}
