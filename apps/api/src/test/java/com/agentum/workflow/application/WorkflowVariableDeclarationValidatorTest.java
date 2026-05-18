package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.shared.api.ApiException;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowVariableDeclarationValidatorTest {

    private final WorkflowVariableDeclarationValidator validator = new WorkflowVariableDeclarationValidator();

    @Test
    void shouldAcceptVariableDeclarationsMatchingNodeOutputs() {
        assertThatCode(() -> validator.validate(
            List.of(
                node("trigger", List.of("starter")),
                node("delivery", List.of("delivery_record"))
            ),
            List.of(
                variable("starter", "string", "trigger"),
                variable("delivery_record", "object", "delivery")
            )
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectInvalidVariableName() {
        assertThatThrownBy(() -> validator.validate(
            List.of(node("trigger", List.of("Starter"))),
            List.of(variable("Starter", "string", "trigger"))
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("变量名必须以小写字母开头，且只能包含小写字母、数字和下划线");
    }

    @Test
    void shouldRejectMissingDeclarationForNodeOutput() {
        assertThatThrownBy(() -> validator.validate(
            List.of(node("trigger", List.of("starter"))),
            List.of()
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("请为每个节点输出变量补齐唯一声明");
    }

    @Test
    void shouldRejectDeclarationWhoseSourceNodeDoesNotOutputVariable() {
        assertThatThrownBy(() -> validator.validate(
            List.of(
                node("trigger", List.of("starter")),
                node("delivery", List.of("delivery_record"))
            ),
            List.of(
                variable("starter", "string", "delivery"),
                variable("delivery_record", "object", "delivery")
            )
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("变量声明必须对应来源节点的输出变量");
    }

    private static WorkflowDraftApi.WorkflowNodeDraft node(String nodeId, List<String> outputVariables) {
        return new WorkflowDraftApi.WorkflowNodeDraft(nodeId, "trigger", nodeId, 0, 0, List.of(), outputVariables, Map.of());
    }

    private static WorkflowDraftApi.WorkflowVariableDraft variable(String name, String type, String sourceNode) {
        return new WorkflowDraftApi.WorkflowVariableDraft(name, type, sourceNode, "", Map.of(), false, false);
    }
}
