package com.agentum.agent.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 运行安全边界。
 *
 * <p>业务上的循环次数必须由每个流程智能体节点配置；这里仅提供平台级上限，
 * 防止异常配置导致模型无限循环或费用失控。</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "agentum.runtime.agent")
public class AgentRuntimeProperties {

    /** 仅用于流程设计器创建节点时初始化表单，实际运行值必须写入节点配置。 */
    @Min(1)
    private int suggestedIterationsPerTurn;

    /** 平台允许单个智能体在一次初始执行或追问中的最大推理次数。 */
    @Min(1)
    private int maxIterationsPerTurn;

    public int getSuggestedIterationsPerTurn() {
        return suggestedIterationsPerTurn;
    }

    public void setSuggestedIterationsPerTurn(int suggestedIterationsPerTurn) {
        this.suggestedIterationsPerTurn = suggestedIterationsPerTurn;
    }

    public int getMaxIterationsPerTurn() {
        return maxIterationsPerTurn;
    }

    public void setMaxIterationsPerTurn(int maxIterationsPerTurn) {
        this.maxIterationsPerTurn = maxIterationsPerTurn;
    }
}
