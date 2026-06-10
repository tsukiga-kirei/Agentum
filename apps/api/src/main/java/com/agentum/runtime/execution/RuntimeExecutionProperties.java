package com.agentum.runtime.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行态异步执行配置。
 *
 * <p>节点执行超时（node-timeout-seconds）通过环境变量 AGENTUM_RUNTIME_NODE_TIMEOUT_SECONDS 覆盖，
 * 保证单个智能体节点不会无限占用执行 Worker；超时后由回收器中止并允许用户重新执行。</p>
 */
@ConfigurationProperties(prefix = "agentum.runtime")
public class RuntimeExecutionProperties {

    private final Execution execution = new Execution();
    private final Redis redis = new Redis();
    private final Rabbitmq rabbitmq = new Rabbitmq();

    public Execution getExecution() {
        return execution;
    }

    public Redis getRedis() {
        return redis;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public static class Execution {
        /** 单节点执行超时秒数，超时后作业被回收并标记失败。 */
        private long nodeTimeoutSeconds = 1800;
        /** MQ 消费者并发数：决定同时可执行的节点作业数量。 */
        private int workerConcurrency = 4;
        /** 同一节点作业最大尝试次数（模型 429/5xx 自动重试上限）。 */
        private int maxAttempts = 3;
        /** 智能体集群并行执行时的最大并发子智能体数量。 */
        private int clusterParallelism = 4;

        public long getNodeTimeoutSeconds() {
            return nodeTimeoutSeconds;
        }

        public void setNodeTimeoutSeconds(long nodeTimeoutSeconds) {
            this.nodeTimeoutSeconds = nodeTimeoutSeconds;
        }

        public int getWorkerConcurrency() {
            return workerConcurrency;
        }

        public void setWorkerConcurrency(int workerConcurrency) {
            this.workerConcurrency = workerConcurrency;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getClusterParallelism() {
            return clusterParallelism;
        }

        public void setClusterParallelism(int clusterParallelism) {
            this.clusterParallelism = clusterParallelism;
        }
    }

    public static class Redis {
        /** 每个 run 的进度 Stream 最大条目数（近似裁剪）。 */
        private long streamMaxLen = 5000;
        /** 执行租约 TTL，Worker 心跳续期；租约消失即认定执行进程已死亡。 */
        private long leaseTtlSeconds = 300;
        /** 节点 running 且无租约超过该秒数后由回收器标记失败。 */
        private long staleNodeThresholdSeconds = 120;

        public long getStreamMaxLen() {
            return streamMaxLen;
        }

        public void setStreamMaxLen(long streamMaxLen) {
            this.streamMaxLen = streamMaxLen;
        }

        public long getLeaseTtlSeconds() {
            return leaseTtlSeconds;
        }

        public void setLeaseTtlSeconds(long leaseTtlSeconds) {
            this.leaseTtlSeconds = leaseTtlSeconds;
        }

        public long getStaleNodeThresholdSeconds() {
            return staleNodeThresholdSeconds;
        }

        public void setStaleNodeThresholdSeconds(long staleNodeThresholdSeconds) {
            this.staleNodeThresholdSeconds = staleNodeThresholdSeconds;
        }
    }

    public static class Rabbitmq {
        private String exchange = "agentum.runtime";
        private String queueNodeExecute = "runtime.node.execute";
        private String queueNodeExecuteDlq = "runtime.node.execute.dlq";

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getQueueNodeExecute() {
            return queueNodeExecute;
        }

        public void setQueueNodeExecute(String queueNodeExecute) {
            this.queueNodeExecute = queueNodeExecute;
        }

        public String getQueueNodeExecuteDlq() {
            return queueNodeExecuteDlq;
        }

        public void setQueueNodeExecuteDlq(String queueNodeExecuteDlq) {
            this.queueNodeExecuteDlq = queueNodeExecuteDlq;
        }
    }
}
