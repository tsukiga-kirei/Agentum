package com.agentum.schedule.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.schedule.interfaces.WorkflowScheduleApi;
import com.agentum.shared.api.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowScheduleServiceTest {

    @Test
    void shouldRejectScheduleWhenWorkflowContainsOptionalAttachmentField() {
        WorkflowScheduleApi.InputFieldRow attachmentField = new WorkflowScheduleApi.InputFieldRow(
            "input-node", "材料提交", "materials", "附件材料", "请选择附件", false,
            "file", "file", List.of(), "", "fixed", "", "date", true
        );

        assertThatThrownBy(() -> WorkflowScheduleService.validateScheduleInputCompatibility(List.of(attachmentField)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("附件字段")
            .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getCode())
                .isEqualTo("SCHEDULE_ATTACHMENT_INPUT_UNSUPPORTED"));
    }
}
