package com.agentum.workflow.application;

/**
 * 新建单智能体/子智能体时预填的默认提示词，创建后用户可自行修改。
 */
public final class WorkflowPromptDefaults {

    public static final String DEFAULT_SYSTEM_PROMPT = "请配置这个智能体的角色、任务边界和输出要求。";
    public static final String DEFAULT_USER_PROMPT = "请基于已产生的可引用内容完成本步骤任务。";
    public static final String DEFAULT_CLUSTER_USER_PROMPT = "请基于已产生的可引用内容完成本智能体任务。";

    private WorkflowPromptDefaults() {
    }
}
