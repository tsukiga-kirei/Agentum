package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowPublishValidatorTest {

    private final WorkflowPublishValidator validator = new WorkflowPublishValidator();

    @Test
    void shouldAcceptConnectedAcyclicGraphWithResolvableVariables() {
        WorkflowDraftApi.WorkflowPublishValidationResult result = validator.validate(
            List.of(
                node("trigger", "trigger", List.of(), List.of("starter")),
                node("input", "user_input", List.of("starter"), List.of("materials")),
                node("delivery", "delivery", List.of("materials"), List.of("delivery_record"))
            ),
            List.of(
                edge("e1", "trigger", "input"),
                edge("e2", "input", "delivery")
            )
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.edgeCount()).isEqualTo(2);
    }

    @Test
    void shouldReportGraphShapeProblemsBeforePublishing() {
        WorkflowDraftApi.WorkflowPublishValidationResult result = validator.validate(
            List.of(
                node("trigger_a", "trigger", List.of(), List.of("starter")),
                node("trigger_b", "trigger", List.of(), List.of("starter_2")),
                node("analysis", "agent", List.of("unknown"), List.of("report"))
            ),
            List.of()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
            .extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_TRIGGER_DUPLICATED",
                "WORKFLOW_VALIDATION_DELIVERY_REQUIRED",
                "WORKFLOW_VALIDATION_NODE_INCOMING_REQUIRED",
                "WORKFLOW_VALIDATION_NODE_OUTGOING_REQUIRED"
            );
    }

    @Test
    void shouldRejectCycleAndUnreachableVariableDependencies() {
        WorkflowDraftApi.WorkflowPublishValidationResult result = validator.validate(
            List.of(
                node("trigger", "trigger", List.of(), List.of("starter")),
                node("analysis", "agent", List.of("future_value"), List.of("report")),
                node("delivery", "delivery", List.of("report"), List.of("delivery_record")),
                node("orphan", "agent", List.of(), List.of("future_value"))
            ),
            List.of(
                edge("e1", "trigger", "analysis"),
                edge("e2", "analysis", "delivery"),
                edge("e3", "delivery", "analysis")
            )
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
            .extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_GRAPH_CYCLE",
                "WORKFLOW_VALIDATION_NODE_UNREACHABLE",
                "WORKFLOW_VALIDATION_INPUT_VARIABLE_UNRESOLVED"
            );
    }

    @Test
    void shouldRejectEdgesThatDoNotMatchLinearBrickOrder() {
        WorkflowDraftApi.WorkflowPublishValidationResult result = validator.validate(
            List.of(
                node("trigger", "trigger", List.of(), List.of("starter")),
                node("input", "user_input", List.of("starter"), List.of("materials")),
                node("analysis", "agent", List.of("materials"), List.of("report")),
                node("delivery", "delivery", List.of("report"), List.of("delivery_record"))
            ),
            List.of(
                edge("e1", "trigger", "input"),
                edge("e2", "input", "delivery"),
                edge("e3", "analysis", "delivery")
            )
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
            .extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_LINEAR_EDGE_INVALID",
                "WORKFLOW_VALIDATION_LINEAR_EDGE_REQUIRED"
            );
    }

    @Test
    void shouldRejectMultipleDeliveryNodesAndDeliveryNotAtLastStep() {
        WorkflowDraftApi.WorkflowPublishValidationResult duplicated = validator.validate(
            List.of(
                node("trigger", "trigger", List.of(), List.of("starter")),
                node("delivery_a", "delivery", List.of("starter"), List.of("delivery_a")),
                node("delivery_b", "delivery", List.of("starter"), List.of("delivery_b"))
            ),
            List.of(
                edge("e1", "trigger", "delivery_a"),
                edge("e2", "delivery_a", "delivery_b")
            )
        );

        assertThat(duplicated.valid()).isFalse();
        assertThat(duplicated.issues())
            .extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_DELIVERY_DUPLICATED",
                "WORKFLOW_VALIDATION_DELIVERY_MUST_BE_TERMINAL"
            );

        WorkflowDraftApi.WorkflowPublishValidationResult notLast = validator.validate(
            List.of(
                node("trigger", "trigger", List.of(), List.of("starter")),
                node("delivery", "delivery", List.of("starter"), List.of("delivery_record")),
                node("analysis", "agent", List.of("delivery_record"), List.of("report"))
            ),
            List.of(
                edge("e1", "trigger", "delivery"),
                edge("e2", "delivery", "analysis")
            )
        );

        assertThat(notLast.valid()).isFalse();
        assertThat(notLast.issues())
            .extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_DELIVERY_MUST_BE_LAST",
                "WORKFLOW_VALIDATION_DELIVERY_MUST_BE_TERMINAL"
            );
    }

    private static WorkflowDraftApi.WorkflowNodeRow node(
        String nodeId,
        String nodeType,
        List<String> inputVariables,
        List<String> outputVariables
    ) {
        return new WorkflowDraftApi.WorkflowNodeRow(nodeId, nodeType, nodeId, 0, 0, inputVariables, outputVariables, Map.of());
    }

    private static WorkflowDraftApi.WorkflowEdgeRow edge(String edgeId, String sourceNodeId, String targetNodeId) {
        return new WorkflowDraftApi.WorkflowEdgeRow(edgeId, sourceNodeId, targetNodeId, "", "");
    }
}
