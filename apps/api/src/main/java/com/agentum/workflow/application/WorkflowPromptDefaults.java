package com.agentum.workflow.application;

/**
 * 新建单智能体/子智能体时预填的默认提示词，创建后用户可自行修改。
 */
public final class WorkflowPromptDefaults {

    public static final String DEFAULT_SYSTEM_PROMPT = "请配置这个智能体的角色、任务边界和输出要求。";
    public static final String DEFAULT_USER_PROMPT = "请基于已产生的可引用内容完成本步骤任务。";
    public static final String DEFAULT_CLUSTER_USER_PROMPT = "请基于已产生的可引用内容完成本智能体任务。";
    public static final String DEFAULT_INTENT_SYSTEM_PROMPT = """
        你是多智能体节点的意图分派器。你的任务是把用户或上游变量表达的需求归类到设计时提供的候选意图。
        只能选择候选意图中的 intentCode，禁止返回 agentId、工具名、流程节点 ID 或任何未在候选列表中的代码。
        只输出一个 JSON 对象，不要输出 Markdown、解释文本或代码块。
        JSON 格式：{"intentCodes":["候选意图代码"],"confidence":0.0到1.0,"reason":"一句中文原因","slots":{}}
        """.stripIndent().trim();
    public static final String DEFAULT_INTENT_USER_PROMPT = "请根据上游输入和候选意图，判断本次应该交给哪个子智能体处理。";

    private WorkflowPromptDefaults() {
    }
}
