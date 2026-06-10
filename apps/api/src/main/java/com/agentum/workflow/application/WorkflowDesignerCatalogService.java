package com.agentum.workflow.application;

import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WorkflowDesignerCatalogService {

    public WorkflowDraftApi.WorkflowDesignerCatalog getCatalog() {
        // 积木模板由后端统一下发，前端只负责渲染和保存设计结果，避免不同页面各自沉淀不可追踪的默认配置。
        return new WorkflowDraftApi.WorkflowDesignerCatalog(
            systemTrigger(),
            List.of(inputBrick(), agentBrick(), clusterBrick(), deliveryBrick()),
            variableMetadata()
        );
    }

    private WorkflowDraftApi.WorkflowBrickTemplate systemTrigger() {
        return new WorkflowDraftApi.WorkflowBrickTemplate(
            "trigger",
            "系统触发",
            "手动、定时或外部事件触发流程。",
            "trigger",
            "手动发起",
            "业务人员从工作台发起流程，系统自动写入发起人和发起时间。",
            "starter",
            "starter",
            List.of(),
            List.of("starter", "started_at"),
            Map.of("brickType", "trigger"),
            "已完成",
            "一次性输出",
            0,
            false
        );
    }

    private WorkflowDraftApi.WorkflowBrickTemplate inputBrick() {
        return new WorkflowDraftApi.WorkflowBrickTemplate(
            "input",
            "输入节点",
            "配置用户需要填写的输入框和输出参数。",
            "user_input",
            "输入信息",
            "配置用户需要填写的输入框。",
            "input",
            "input_1",
            List.of("starter"),
            List.of("input_1"),
            Map.of(
                "brickType", "input",
                "inputFields", List.of(Map.of(
                    "id", "field_1",
                    "label", "业务输入",
                    "variable", "input_1",
                    "placeholder", "请输入业务资料"
                ))
            ),
            "等待输入",
            "一次性输出",
            0,
            false
        );
    }

    private WorkflowDraftApi.WorkflowBrickTemplate agentBrick() {
        return new WorkflowDraftApi.WorkflowBrickTemplate(
            "agent",
            "单智能体节点",
            "从权限内能力选择智能体模板、提示词模板、MCP 和 Skill。",
            "agent",
            "单智能体处理",
            "选择或配置一个智能体，加载提示词模板、MCP 和 Skill 完成任务。",
            "agent_response",
            "agent_response",
            List.of(),
            List.of("agent_response"),
            Map.of(
                "brickType", "agent",
                "agentSource", "custom",
                "agentAssetId", "custom",
                "promptTemplateId", "none",
                "systemPromptTemplateId", "none",
                "userPromptTemplateId", "none",
                "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT,
                "mcpServices", List.of(),
                "skills", List.of()
            ),
            "待配置",
            "追问确认",
            0,
            true
        );
    }

    private WorkflowDraftApi.WorkflowBrickTemplate clusterBrick() {
        return new WorkflowDraftApi.WorkflowBrickTemplate(
            "cluster",
            "智能体集群节点",
            "组合多个子智能体并配置拼接与汇总规则。",
            "parallel_group",
            "智能体集群处理",
            "多个智能体并行处理，再按拼接规则汇总输出。",
            "cluster_result",
            "cluster_result",
            List.of(),
            List.of("cluster_result"),
            Map.of(
                "brickType", "cluster",
                "clusterAgents", List.of(clusterAgent(1), clusterAgent(2)),
                "mergeRule", "按业务顺序合并多个智能体输出，冲突内容保留来源并交给用户审查。"
            ),
            "待配置",
            "追问确认",
            2,
            true
        );
    }

    private WorkflowDraftApi.WorkflowBrickTemplate deliveryBrick() {
        return new WorkflowDraftApi.WorkflowBrickTemplate(
            "delivery",
            "交付节点",
            "绑定权限内交付能力并配置交付内容。",
            "delivery",
            "交付结果",
            "配置最终交付方式和交付内容。",
            "delivery_record",
            "delivery_record",
            List.of(),
            List.of("delivery_record"),
            Map.of(
                "brickType", "delivery",
                "deliveryMode", "capability",
                "deliveryCapabilityId", "none",
                "deliveryTarget", "说明交付目标、模板和确认方式。"
            ),
            "待配置",
            "一次性输出",
            1,
            false
        );
    }

    private Map<String, Object> clusterAgent(int index) {
        Map<String, Object> agent = new java.util.LinkedHashMap<>();
        agent.put("id", "cluster_agent_" + index);
        agent.put("name", "子智能体 " + index);
        agent.put("agentAssetId", "custom");
        agent.put("promptTemplateId", "none");
        agent.put("systemPromptTemplateId", "none");
        agent.put("userPromptTemplateId", "none");
        agent.put("skillIds", List.of());
        agent.put("mcpIds", List.of());
        agent.put("systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT);
        agent.put("userPrompt", WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT);
        agent.put("output", "agent_" + index + "_output");
        return agent;
    }

    private Map<String, WorkflowDraftApi.WorkflowVariableTemplate> variableMetadata() {
        return Map.of(
            "starter", new WorkflowDraftApi.WorkflowVariableTemplate("string", false, false, "流程发起人标识"),
            "started_at", new WorkflowDraftApi.WorkflowVariableTemplate("string", false, false, "流程发起时间"),
            "input_1", new WorkflowDraftApi.WorkflowVariableTemplate("string", false, false, "用户输入内容"),
            "agent_response", new WorkflowDraftApi.WorkflowVariableTemplate("object", false, false, "单智能体回复内容"),
            "cluster_result", new WorkflowDraftApi.WorkflowVariableTemplate("object", false, true, "智能体集群汇总结果"),
            "delivery_record", new WorkflowDraftApi.WorkflowVariableTemplate("object", false, true, "交付记录")
        );
    }
}
