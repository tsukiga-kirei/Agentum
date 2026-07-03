package com.agentum.workbench.application;

import com.agentum.agent.application.AgentRuntimeRequest;
import com.agentum.agent.application.AgentRuntimeService;
import com.agentum.delivery.application.DeliveryRuntimeRequest;
import com.agentum.delivery.application.DeliveryRuntimeService;
import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.agentum.runtime.messaging.NodeExecuteCommand;
import com.agentum.runtime.messaging.NodeExecuteCommandPublisher;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.agentum.shared.api.ApiException;
import com.agentum.workflow.domain.WorkflowClusterAgentRunEntity;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import com.agentum.workflow.infrastructure.WorkflowClusterAgentRunRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunExecutionJobRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.application.WorkflowRuntimeSystemVariables;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 节点执行服务（Worker 侧）：消费 MQ 节点执行命令，完成单个节点的真实执行。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>执行与浏览器 SSE 连接完全解耦，进度事件写入 Redis Stream，由 API 中继转发；</li>
 *   <li>同一 runId 通过 Redis 租约互斥，执行期间心跳续期并向前端发 heartbeat；</li>
 *   <li>模型类瞬时错误（429/5xx）有限次自动重新入队；业务错误直接标记节点失败；</li>
 *   <li>智能体集群子智能体结果逐个落库，失败后「恢复进度」只重跑失败/未完成的子智能体。</li>
 * </ul>
 */
@Service
public class NodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutionService.class);
    /** 心跳/租约续期间隔（秒）：前端看门狗依赖 heartbeat 判定执行进程存活。 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;
    /** 可自动重试的瞬时错误码：均来自模型网关侧 429/5xx 类故障。 */
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
        "MODEL_CALL_FAILED",
        "MODEL_HTTP_ERROR",
        "MODEL_RESPONSE_EMPTY"
    );
    /** 整节点中止类错误码：不算子智能体失败，向上传播终止整个节点执行。 */
    private static final Set<String> ABORT_ERROR_CODES = Set.of(
        "RUN_CANCELLED",
        "WORKBENCH_NODE_EXECUTION_TIMEOUT"
    );

    private final WorkbenchRuntimeService workbenchRuntimeService;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowNodeRunRepository workflowNodeRunRepository;
    private final WorkflowRunExecutionJobRepository jobRepository;
    private final WorkflowClusterAgentRunRepository clusterAgentRunRepository;
    private final AgentRuntimeService agentRuntimeService;
    private final DeliveryRuntimeService deliveryRuntimeService;
    private final WorkflowRuntimeExecutor workflowRuntimeExecutor;
    private final RunProgressStreamWriter streamWriter;
    private final RunExecutionLeaseService leaseService;
    private final RunCancellationGuard cancellationGuard;
    private final NodeExecuteCommandPublisher commandPublisher;
    private final RuntimeExecutionProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService heartbeatScheduler;

    public NodeExecutionService(
        WorkbenchRuntimeService workbenchRuntimeService,
        WorkflowRunRepository workflowRunRepository,
        WorkflowNodeRunRepository workflowNodeRunRepository,
        WorkflowRunExecutionJobRepository jobRepository,
        WorkflowClusterAgentRunRepository clusterAgentRunRepository,
        AgentRuntimeService agentRuntimeService,
        DeliveryRuntimeService deliveryRuntimeService,
        WorkflowRuntimeExecutor workflowRuntimeExecutor,
        RunProgressStreamWriter streamWriter,
        RunExecutionLeaseService leaseService,
        RunCancellationGuard cancellationGuard,
        NodeExecuteCommandPublisher commandPublisher,
        RuntimeExecutionProperties properties,
        Clock clock
    ) {
        this.workbenchRuntimeService = workbenchRuntimeService;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowNodeRunRepository = workflowNodeRunRepository;
        this.jobRepository = jobRepository;
        this.clusterAgentRunRepository = clusterAgentRunRepository;
        this.agentRuntimeService = agentRuntimeService;
        this.deliveryRuntimeService = deliveryRuntimeService;
        this.workflowRuntimeExecutor = workflowRuntimeExecutor;
        this.streamWriter = streamWriter;
        this.leaseService = leaseService;
        this.cancellationGuard = cancellationGuard;
        this.commandPublisher = commandPublisher;
        this.properties = properties;
        this.clock = clock;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "agentum-runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void execute(NodeExecuteCommand command) {
        UUID runId = command.runId();
        WorkflowRunExecutionJobEntity job = jobRepository.findById(command.jobId()).orElse(null);
        if (job == null || !WorkflowRunExecutionJobEntity.STATUS_QUEUED.equals(job.getStatus())) {
            // 幂等保护：作业不存在或已被处理（重复消费 / 已被回收器终态化），直接丢弃。
            log.info("节点执行命令已失效，跳过 jobId={} runId={} requestId={}", command.jobId(), runId, command.requestId());
            return;
        }

        String workerId = "worker-" + UUID.randomUUID();
        if (!tryAcquireExecutionLease(runId, workerId, command)) {
            // 同一 run 已有 Worker 持有 Redis 租约并在执行（含模型长耗时）。丢弃本条重复消息即可，
            // 切勿再次入队——否则多 consumers 会复制命令，形成日志风暴且无法推进。
            WorkflowRunExecutionJobEntity latest = jobRepository.findById(command.jobId()).orElse(null);
            String jobStatus = latest == null ? "missing" : latest.getStatus();
            log.info("执行租约被占用，跳过重复消费 runId={} jobId={} jobStatus={}", runId, command.jobId(), jobStatus);
            return;
        }

        ScheduledFuture<?> heartbeat = null;
        UUID nodeRunId = command.nodeRunId();
        try {
            WorkflowNodeRunEntity node = workflowNodeRunRepository.findById(nodeRunId).orElse(null);
            WorkflowRunEntity run = workflowRunRepository.findById(runId).orElse(null);
            if (node == null || run == null || !isExecutableState(node.getState())) {
                // 节点已被中断/回退/删除：作业按取消收尾，不重复执行。
                finalizeJobCanceled(command.jobId());
                return;
            }
            if (cancellationGuard.isCancelled(runId)) {
                finalizeJobCanceled(command.jobId());
                return;
            }

            Instant now = clock.instant();
            job.markRunning(workerId, now);
            jobRepository.save(job);
            if (job.getDeadlineAt() != null) {
                cancellationGuard.markDeadline(runId, job.getDeadlineAt().toEpochMilli());
            }
            heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> emitHeartbeat(runId, nodeRunId, workerId),
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            );

            log.info(
                "节点执行开始 tenantId={} runId={} nodeRunId={} nodeType={} jobId={} attempt={} workerId={} requestId={}",
                command.tenantId(), runId, nodeRunId, node.getNodeType(), job.getId(), command.attempt(), workerId, command.requestId()
            );

            emit(runId, nodeRunId, "node_started", Map.of(
                "nodeType", node.getNodeType(),
                "nodeName", node.getName()
            ));

            Map<String, Object> variables = variablesBeforeNode(run, node.getSortOrder());
            Map<String, Object> outputs = dispatch(run, node, variables, command.operatorUserId());

            // 中断/restart 后旧 Worker 可能仍持有内存上下文；落库前必须确认本 job 仍为 DB 中的有效 running 作业。
            if (!isJobStillActive(command.jobId())) {
                log.info(
                    "作业已失效，跳过成功落库 tenantId={} runId={} nodeRunId={} jobId={} requestId={}",
                    command.tenantId(), runId, nodeRunId, command.jobId(), command.requestId()
                );
                return;
            }
            cancellationGuard.assertExecutable(runId);
            workbenchRuntimeService.saveNodeSuccess(runId, nodeRunId, outputs, command.operatorUserId());
            WorkflowNodeRunEntity nodeAfterSave = workflowNodeRunRepository.findById(nodeRunId).orElse(null);
            if (nodeAfterSave == null || !"completed".equals(nodeAfterSave.getState())) {
                log.info(
                    "节点成功落库未生效，跳过成功收尾 tenantId={} runId={} nodeRunId={} jobId={} requestId={}",
                    command.tenantId(), runId, nodeRunId, command.jobId(), command.requestId()
                );
                return;
            }
            finalizeJobSucceeded(command.jobId());

            emit(runId, nodeRunId, "node_completed", Map.of("outputs", outputs));
            emitPostCompletionState(runId, nodeRunId);
            streamWriter.append(runId, "message", "[DONE]");

            log.info(
                "节点执行完成 tenantId={} runId={} nodeRunId={} jobId={} requestId={}",
                command.tenantId(), runId, nodeRunId, job.getId(), command.requestId()
            );
        } catch (ApiException exception) {
            handleExecutionFailure(command, exception.getCode(), exception.getMessage());
        } catch (Exception exception) {
            log.error(
                "节点执行系统异常 tenantId={} runId={} nodeRunId={} jobId={} requestId={}",
                command.tenantId(), runId, nodeRunId, command.jobId(), command.requestId(), exception
            );
            handleExecutionFailure(command, "WORKBENCH_NODE_EXECUTION_FAILED", "节点执行异常，请重新执行或联系管理员");
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            leaseService.release(runId, workerId);
        }
    }

    /**
     * 获取执行租约。若 Redis 有锁但 DB 无 running 作业，视为孤儿锁清理后重试一次，
     * 与 WorkbenchRuntimeService 入队前清理互为兜底（MQ 重复投递或 API/Worker 竞态）。
     */
    private boolean tryAcquireExecutionLease(UUID runId, String workerId, NodeExecuteCommand command) {
        if (leaseService.tryAcquire(runId, workerId)) {
            return true;
        }
        boolean hasRunningJob = !jobRepository.findByRunIdAndStatusIn(
            runId,
            List.of(WorkflowRunExecutionJobEntity.STATUS_RUNNING)
        ).isEmpty();
        if (hasRunningJob) {
            return false;
        }
        log.warn(
            "执行租约被占且无 running 作业，按孤儿锁清理后重试 runId={} jobId={} requestId={}",
            runId,
            command.jobId(),
            command.requestId()
        );
        leaseService.forceRelease(runId);
        return leaseService.tryAcquire(runId, workerId);
    }

    private Map<String, Object> dispatch(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        Map<String, Object> variables,
        UUID operatorUserId
    ) {
        return switch (node.getNodeType()) {
            case "agent" -> executeAgentNode(run, node, variables, operatorUserId);
            case "parallel_group" -> executeClusterNode(run, node, variables, operatorUserId);
            case "delivery" -> deliveryRuntimeService.execute(new DeliveryRuntimeRequest(
                run,
                node,
                node.getConfigSnapshot(),
                variables,
                operatorUserId
            )).outputs();
            default -> workflowRuntimeExecutor.execute(new WorkflowRuntimeExecutor.ExecutionRequest(
                run,
                node,
                variables,
                operatorUserId
            )).outputs();
        };
    }

    // ------------------------------------------------------------------
    // 单智能体节点
    // ------------------------------------------------------------------

    private Map<String, Object> executeAgentNode(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        Map<String, Object> variables,
        UUID operatorUserId
    ) {
        UUID runId = run.getId();
        UUID nodeRunId = node.getId();
        emit(runId, nodeRunId, "agent_thinking", Map.of(
            "phase", "preparing",
            "message", "正在装配 Agent 工具箱与上下文..."
        ));

        AgentRuntimeRequest agentRequest = new AgentRuntimeRequest(
            run,
            node,
            node.getConfigSnapshot(),
            variables,
            Map.of(),
            operatorUserId
        );

        return new LinkedHashMap<>(agentRuntimeService.executeStreaming(agentRequest, new AgentRuntimeService.AgentRuntimeEventSink() {
            private final StringBuilder accumulated = new StringBuilder();

            @Override
            public void onPhase(String phase, String message) {
                emit(runId, nodeRunId, "agent_thinking", Map.of("phase", phase, "message", message));
            }

            @Override
            public void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
                emit(runId, nodeRunId, "agent_tool_call", Map.of(
                    "toolName", toolName,
                    "toolType", toolType,
                    "status", status,
                    // 工具详情会在运行页展开显示；保留换行和正文，仅限制事件体长度，避免 Redis Stream 被超大响应撑满。
                    "result", toolResultForEvent(result),
                    "durationMs", durationMs
                ));
            }

            @Override
            public void onToken(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onModelContent(String deltaContent, String accumulatedContent) {
                emitStreaming("model_content", deltaContent, accumulatedContent);
            }

            @Override
            public void onFinalAnswerContent(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onReasoningContent(String deltaContent, String accumulatedContent) {
                emitStreaming("reasoning", deltaContent, accumulatedContent);
            }

            private void emitStreaming(String streamKind, String deltaContent, String accumulatedContent) {
                if (accumulatedContent != null && !accumulatedContent.isBlank()) {
                    accumulated.setLength(0);
                    accumulated.append(accumulatedContent);
                } else if (deltaContent != null && !deltaContent.isBlank()) {
                    accumulated.append(deltaContent);
                }
                emit(runId, nodeRunId, "agent_streaming", Map.of(
                    "streamKind", streamKind,
                    "deltaContent", deltaContent == null ? "" : deltaContent,
                    "accumulatedContent", accumulated.toString()
                ));
            }

            @Override
            public void onCompleted(String answer) {
                accumulated.setLength(0);
                accumulated.append(answer == null ? "" : answer);
                emit(runId, nodeRunId, "agent_streaming", Map.of(
                    "streamKind", "final_answer",
                    "deltaContent", "",
                    "accumulatedContent", accumulated.toString()
                ));
                emit(runId, nodeRunId, "agent_thinking", Map.of(
                    "phase", "completed",
                    "message", "智能体已完成 final_answer。"
                ));
            }

            @Override
            public void onFailed(String code, String message) {
                emit(runId, nodeRunId, "agent_thinking", Map.of(
                    "phase", "failed",
                    "message", "智能体执行出错: " + message
                ));
            }
        }).outputs());
    }

    // ------------------------------------------------------------------
    // 智能体集群节点（协同处理 / 接力处理 / 意图分派）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeClusterNode(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        Map<String, Object> variables,
        UUID operatorUserId
    ) {
        UUID runId = run.getId();
        UUID nodeRunId = node.getId();
        Object rawAgents = node.getConfigSnapshot().get("clusterAgents");
        if (!(rawAgents instanceof List<?> agents) || agents.isEmpty()) {
            Map<String, Object> output = new LinkedHashMap<>(variables);
            output.put("summary", "智能体集群未配置子智能体，已透传上游变量。");
            return output;
        }

        List<ClusterAgentSlot> slots = new ArrayList<>();
        int index = 0;
        for (Object rawAgent : agents) {
            if (rawAgent instanceof Map<?, ?> rawMap) {
                Map<String, Object> agentConfig = new LinkedHashMap<>((Map<String, Object>) rawMap);
                slots.add(new ClusterAgentSlot(index, stringValue(agentConfig.get("name"), "子智能体 " + (index + 1)), agentConfig));
            }
            index++;
        }

        // 已成功的子智能体直接复用落库结果（恢复进度 / 自动重试场景），并向前端回放 completed 事件。
        Map<Integer, Map<String, Object>> outputsByIndex = new HashMap<>();
        for (WorkflowClusterAgentRunEntity existing : clusterAgentRunRepository.findByNodeRunIdOrderByAgentIndexAsc(nodeRunId)) {
            if (!existing.isSucceeded()) {
                continue;
            }
            Map<String, Object> output = existing.getOutput() == null ? Map.of() : existing.getOutput();
            outputsByIndex.put(existing.getAgentIndex(), output);
            emit(runId, nodeRunId, "cluster_agent", Map.of(
                "agentIndex", existing.getAgentIndex(),
                "agentName", existing.getName(),
                "eventType", "completed",
                "outputSummary", clusterAgentDisplayText(output)
            ));
        }

        String executionMode = ClusterIntentRoutingSupport.normalizeExecutionMode(node.getConfigSnapshot().get("executionMode"));
        List<ClusterAgentSlot> resultSlots = slots;
        List<ClusterAgentOutcome> failures;
        ClusterIntentRoutingSupport.IntentDecision intentDecision = null;
        if (ClusterIntentRoutingSupport.MODE_INTENT.equals(executionMode)) {
            IntentRoutingResult routingResult = routeClusterByIntent(run, node, slots, variables, operatorUserId);
            intentDecision = routingResult.decision();
            resultSlots = routingResult.selectedSlots();
            if (resultSlots.isEmpty()) {
                if (!intentDecision.fixedReply().isBlank()) {
                    return fixedIntentReplyResult(variables, node.getConfigSnapshot(), intentDecision);
                }
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CLUSTER_INTENT_NO_MATCH",
                    "意图分派未命中任何可执行子智能体，请检查意图配置或其他情况处理策略"
                );
            }
            failures = executeClusterParallel(run, node, resultSlots, variables, outputsByIndex, operatorUserId);
        } else if (ClusterIntentRoutingSupport.MODE_RELAY.equals(executionMode)) {
            failures = executeClusterSequential(run, node, slots, variables, outputsByIndex, operatorUserId);
        } else {
            failures = executeClusterParallel(run, node, slots, variables, outputsByIndex, operatorUserId);
        }

        cancellationGuard.assertExecutable(runId);
        if (!failures.isEmpty()) {
            Optional<String> retryableCode = failures.stream()
                .map(ClusterAgentOutcome::errorCode)
                .filter(RETRYABLE_ERROR_CODES::contains)
                .findFirst();
            String errorCode = retryableCode.orElse("CLUSTER_AGENT_FAILED");

            String detail = failures.stream()
                .map(failure -> failure.name() + "（" + failure.errorMessage() + "）")
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
            // 已成功子智能体结果已落库保留，恢复进度只会重跑失败部分。
            throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                errorCode,
                failures.size() + " 个子智能体执行失败：" + detail
            );
        }

        Map<String, Object> result = new LinkedHashMap<>(variables);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ClusterAgentSlot slot : resultSlots) {
            Map<String, Object> output = outputsByIndex.get(slot.index());
            if (output == null) {
                continue;
            }
            result.putAll(output);
            String displayText = clusterAgentDisplayText(output);

            String outputVarName = stringValue(slot.config().get("output"), "");
            if (!outputVarName.isBlank()) {
                result.put(outputVarName, displayText);
            }

            Map<String, Object> summaryEntry = new LinkedHashMap<>();
            summaryEntry.put("name", slot.name());
            summaryEntry.put("outputVariable", outputVarName);
            summaryEntry.put("status", "completed");
            summaryEntry.put("final_answer", displayText);
            summaryEntry.put("summary", summarizeText(displayText));
            summaryEntry.put("tokenUsage", output.getOrDefault("tokenUsage", Map.of()));
            summaryEntry.put("chatMessages", output.getOrDefault("chatMessages", List.of()));
            summaries.add(summaryEntry);
        }
        result.put("clusterAgents", summaries);
        String finalAnswer = ClusterOutputSupport.finalAnswer(node.getConfigSnapshot(), result, summaries);
        String clusterOutputVariable = ClusterOutputSupport.outputVariable(node.getConfigSnapshot());
        result.put("final_answer", finalAnswer);
        result.put("agent_response", finalAnswer);
        result.put(clusterOutputVariable, finalAnswer);
        result.put(ClusterIntentRoutingSupport.DEFAULT_INTENT_OUTPUT_VARIABLE, finalAnswer);
        if (intentDecision != null) {
            result.put("intentRouting", intentRoutingSummary(intentDecision));
        }
        result.put("summary", "智能体集群已完成 " + summaries.size() + " 个子智能体。");
        return result;
    }

    /**
     * 意图分派先用受控分类器得到 intentCode，再按设计时映射筛选子智能体。
     * 分类器不会继承任何 Skill/MCP，模型输出也只接受白名单 intentCode，避免运行时动态选择未授权能力。
     */
    private IntentRoutingResult routeClusterByIntent(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        List<ClusterAgentSlot> slots,
        Map<String, Object> variables,
        UUID operatorUserId
    ) {
        Map<String, Object> nodeConfig = node.getConfigSnapshot();
        List<Map<String, Object>> agentConfigs = slots.stream().map(ClusterAgentSlot::config).toList();
        List<ClusterIntentRoutingSupport.IntentRoute> routes = ClusterIntentRoutingSupport.intentRoutes(nodeConfig, agentConfigs);
        emit(run.getId(), node.getId(), "cluster_intent", Map.of(
            "eventType", "started",
            "routeCount", routes.size()
        ));

        Map<String, Object> classifierOutput = agentRuntimeService.executeStreaming(
            new AgentRuntimeRequest(
                run,
                node,
                ClusterIntentRoutingSupport.classifierConfig(nodeConfig, agentConfigs, routes),
                variables,
                Map.of(),
                operatorUserId
            ),
            intentClassifierEventSink(run.getId(), node.getId())
        ).outputs();
        ClusterIntentRoutingSupport.IntentDecision decision = ClusterIntentRoutingSupport.decide(
            nodeConfig,
            routes,
            agentConfigs,
            classifierOutput
        );
        Map<Integer, ClusterAgentSlot> slotByIndex = slots.stream()
            .collect(Collectors.toMap(ClusterAgentSlot::index, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<ClusterAgentSlot> selectedSlots = decision.selectedAgentIndexes().stream()
            .map(slotByIndex::get)
            .filter(slot -> slot != null)
            .toList();
        emit(run.getId(), node.getId(), "cluster_intent", Map.of(
            "eventType", "completed",
            "requestedCodes", decision.requestedCodes(),
            "selectedCodes", decision.selectedCodes(),
            "selectedAgentIndexes", decision.selectedAgentIndexes(),
            "reason", decision.reason(),
            "fallbackMode", decision.fallbackMode(),
            "usedFallback", decision.usedFallback()
        ));
        return new IntentRoutingResult(decision, selectedSlots);
    }

    /**
     * 接力处理：子智能体按下标依次运行，后续子智能体可使用前序输出变量；
     * 任一失败立即停止后续执行（链式依赖下继续执行无意义）。
     */
    private List<ClusterAgentOutcome> executeClusterSequential(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        List<ClusterAgentSlot> slots,
        Map<String, Object> variables,
        Map<Integer, Map<String, Object>> outputsByIndex,
        UUID operatorUserId
    ) {
        List<ClusterAgentOutcome> failures = new ArrayList<>();
        Map<String, Object> currentVars = new LinkedHashMap<>(variables);
        for (ClusterAgentSlot slot : slots) {
            Map<String, Object> reused = outputsByIndex.get(slot.index());
            if (reused != null) {
                currentVars.putAll(reused);
                continue;
            }
            ClusterAgentOutcome outcome = runClusterAgent(run, node, slot, new LinkedHashMap<>(currentVars), operatorUserId);
            if (outcome.success()) {
                outputsByIndex.put(slot.index(), outcome.output());
                currentVars.putAll(outcome.output());
            } else {
                failures.add(outcome);
                break;
            }
        }
        return failures;
    }

    /**
     * 协同处理：子智能体彼此独立，仅依赖上游变量；并发度受 cluster-parallelism 配置限制。
     * 单个失败不影响其他子智能体收尾，结果逐个落库后统一判定节点失败。
     */
    private List<ClusterAgentOutcome> executeClusterParallel(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        List<ClusterAgentSlot> slots,
        Map<String, Object> variables,
        Map<Integer, Map<String, Object>> outputsByIndex,
        UUID operatorUserId
    ) {
        List<ClusterAgentSlot> pending = slots.stream()
            .filter(slot -> !outputsByIndex.containsKey(slot.index()))
            .toList();
        if (pending.isEmpty()) {
            return List.of();
        }
        int parallelism = Math.max(1, Math.min(properties.getExecution().getClusterParallelism(), pending.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "agentum-cluster-agent");
            thread.setDaemon(true);
            return thread;
        });
        List<ClusterAgentOutcome> failures = new ArrayList<>();
        ApiException abortException = null;
        try {
            List<CompletableFuture<ClusterAgentOutcome>> futures = pending.stream()
                .map(slot -> CompletableFuture.supplyAsync(
                    () -> runClusterAgent(run, node, slot, new LinkedHashMap<>(variables), operatorUserId),
                    pool
                ))
                .toList();
            for (CompletableFuture<ClusterAgentOutcome> future : futures) {
                try {
                    ClusterAgentOutcome outcome = future.join();
                    if (outcome.success()) {
                        outputsByIndex.put(outcome.index(), outcome.output());
                    } else {
                        failures.add(outcome);
                    }
                } catch (Exception exception) {
                    ApiException abort = unwrapAbortException(exception);
                    if (abort != null) {
                        abortException = abort;
                    } else {
                        log.error("集群子智能体并行任务异常 runId={} nodeRunId={}", run.getId(), node.getId(), exception);
                    }
                }
            }
        } finally {
            pool.shutdown();
        }
        if (abortException != null) {
            // 用户中断 / 超时：向上传播终止整个节点，不按子智能体失败处理。
            throw abortException;
        }
        return failures;
    }

    private ClusterAgentOutcome runClusterAgent(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity node,
        ClusterAgentSlot slot,
        Map<String, Object> vars,
        UUID operatorUserId
    ) {
        UUID runId = run.getId();
        UUID nodeRunId = node.getId();
        cancellationGuard.assertExecutable(runId);

        // 旧的非成功行（上次失败/中断残留）先删除，再插入本次 running 行。
        clusterAgentRunRepository.findByNodeRunIdAndAgentIndex(nodeRunId, slot.index())
            .filter(existing -> !existing.isSucceeded())
            .ifPresent(clusterAgentRunRepository::delete);
        WorkflowClusterAgentRunEntity row = WorkflowClusterAgentRunEntity.started(
            runId, nodeRunId, run.getTenantId(), slot.index(), slot.name(), clock.instant()
        );
        saveClusterRowIfNodeRunning(nodeRunId, row);

        emit(runId, nodeRunId, "cluster_agent", Map.of(
            "agentIndex", slot.index(),
            "agentName", slot.name(),
            "eventType", "started"
        ));

        try {
            Map<String, Object> agentOutput = agentRuntimeService.executeStreaming(
                new AgentRuntimeRequest(run, node, slot.config(), vars, Map.of(), operatorUserId),
                clusterAgentEventSink(runId, nodeRunId, slot)
            ).outputs();

            row.succeed(agentOutput, clock.instant());
            saveClusterRowIfNodeRunning(nodeRunId, row);

            emit(runId, nodeRunId, "cluster_agent", Map.of(
                "agentIndex", slot.index(),
                "agentName", slot.name(),
                "eventType", "completed",
                "outputSummary", clusterAgentDisplayText(agentOutput)
            ));
            return ClusterAgentOutcome.success(slot.index(), slot.name(), agentOutput);
        } catch (ApiException exception) {
            if (ABORT_ERROR_CODES.contains(exception.getCode())) {
                // 中断/超时不是子智能体业务失败：行保持 running，由中断清理或恢复时删除重跑。
                throw exception;
            }
            return failClusterAgent(runId, nodeRunId, slot, row, exception.getCode(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            return failClusterAgent(runId, nodeRunId, slot, row, "CLUSTER_AGENT_FAILED", "子智能体执行失败，请稍后重试", exception);
        }
    }

    private ClusterAgentOutcome failClusterAgent(
        UUID runId,
        UUID nodeRunId,
        ClusterAgentSlot slot,
        WorkflowClusterAgentRunEntity row,
        String errorCode,
        String errorMessage,
        Exception exception
    ) {
        log.warn(
            "智能体集群子智能体执行失败 runId={} nodeRunId={} agentIndex={} agentName={} errorCode={}",
            runId, nodeRunId, slot.index(), slot.name(), errorCode, exception
        );
        row.fail(errorCode, errorMessage, clock.instant());
        saveClusterRowIfNodeRunning(nodeRunId, row);
        emit(runId, nodeRunId, "cluster_agent", Map.of(
            "agentIndex", slot.index(),
            "agentName", slot.name(),
            "eventType", "failed",
            "errorCode", errorCode,
            "errorMessage", errorMessage
        ));
        return ClusterAgentOutcome.failure(slot.index(), slot.name(), errorCode, errorMessage);
    }

    private AgentRuntimeService.AgentRuntimeEventSink clusterAgentEventSink(UUID runId, UUID nodeRunId, ClusterAgentSlot slot) {
        return new AgentRuntimeService.AgentRuntimeEventSink() {
            private final StringBuilder subAccumulated = new StringBuilder();

            @Override
            public void onPhase(String phase, String message) {
                emit(runId, nodeRunId, "cluster_agent", Map.of(
                    "agentIndex", slot.index(),
                    "agentName", slot.name(),
                    "eventType", "phase",
                    "phase", phase,
                    "message", message
                ));
            }

            @Override
            public void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
                emit(runId, nodeRunId, "cluster_agent", Map.of(
                    "agentIndex", slot.index(),
                    "agentName", slot.name(),
                    "eventType", "tool_call",
                    "toolName", toolName,
                    "toolType", toolType,
                    "toolStatus", status,
                    "result", toolResultForEvent(result),
                    "durationMs", durationMs
                ));
            }

            @Override
            public void onToken(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onModelContent(String deltaContent, String accumulatedContent) {
                emitStreaming("model_content", deltaContent, accumulatedContent);
            }

            @Override
            public void onFinalAnswerContent(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onReasoningContent(String deltaContent, String accumulatedContent) {
                emitStreaming("reasoning", deltaContent, accumulatedContent);
            }

            private void emitStreaming(String streamKind, String deltaContent, String accumulatedContent) {
                if (accumulatedContent != null && !accumulatedContent.isBlank()) {
                    subAccumulated.setLength(0);
                    subAccumulated.append(accumulatedContent);
                } else if (deltaContent != null && !deltaContent.isBlank()) {
                    subAccumulated.append(deltaContent);
                }
                emit(runId, nodeRunId, "cluster_agent", Map.of(
                    "agentIndex", slot.index(),
                    "agentName", slot.name(),
                    "eventType", "streaming",
                    "streamKind", streamKind,
                    "deltaContent", deltaContent == null ? "" : deltaContent,
                    "accumulatedContent", subAccumulated.toString()
                ));
            }

            @Override
            public void onCompleted(String answer) {
                subAccumulated.setLength(0);
                subAccumulated.append(answer == null ? "" : answer);
                emit(runId, nodeRunId, "cluster_agent", Map.of(
                    "agentIndex", slot.index(),
                    "agentName", slot.name(),
                    "eventType", "streaming",
                    "streamKind", "final_answer",
                    "deltaContent", "",
                    "accumulatedContent", subAccumulated.toString()
                ));
            }

            @Override
            public void onFailed(String code, String message) {
                emit(runId, nodeRunId, "cluster_agent", Map.of(
                    "agentIndex", slot.index(),
                    "agentName", slot.name(),
                    "eventType", "failed",
                    "errorCode", code,
                    "errorMessage", message
                ));
            }
        };
    }

    private AgentRuntimeService.AgentRuntimeEventSink intentClassifierEventSink(UUID runId, UUID nodeRunId) {
        return new AgentRuntimeService.AgentRuntimeEventSink() {
            private final StringBuilder accumulated = new StringBuilder();

            @Override
            public void onPhase(String phase, String message) {
                emit(runId, nodeRunId, "cluster_intent", Map.of(
                    "eventType", "phase",
                    "phase", phase,
                    "message", message
                ));
            }

            @Override
            public void onToolCall(String toolName, String toolType, String status, String result, long durationMs) {
                // 意图分类器不注入 Skill/MCP；如果未来扩展工具，这里仍保留脱敏事件，便于审计异常配置。
                emit(runId, nodeRunId, "cluster_intent", Map.of(
                    "eventType", "tool_call",
                    "toolName", toolName,
                    "toolType", toolType,
                    "toolStatus", status,
                    "result", toolResultForEvent(result),
                    "durationMs", durationMs
                ));
            }

            @Override
            public void onToken(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onModelContent(String deltaContent, String accumulatedContent) {
                emitStreaming("model_content", deltaContent, accumulatedContent);
            }

            @Override
            public void onFinalAnswerContent(String deltaContent, String accumulatedContent) {
                emitStreaming("final_answer", deltaContent, accumulatedContent);
            }

            @Override
            public void onReasoningContent(String deltaContent, String accumulatedContent) {
                emitStreaming("reasoning", deltaContent, accumulatedContent);
            }

            private void emitStreaming(String streamKind, String deltaContent, String accumulatedContent) {
                if (accumulatedContent != null && !accumulatedContent.isBlank()) {
                    accumulated.setLength(0);
                    accumulated.append(accumulatedContent);
                } else if (deltaContent != null && !deltaContent.isBlank()) {
                    accumulated.append(deltaContent);
                }
                emit(runId, nodeRunId, "cluster_intent", Map.of(
                    "eventType", "streaming",
                    "streamKind", streamKind,
                    "deltaContent", deltaContent == null ? "" : deltaContent,
                    "accumulatedContent", accumulated.toString()
                ));
            }

            @Override
            public void onCompleted(String answer) {
                accumulated.setLength(0);
                accumulated.append(answer == null ? "" : answer);
                emit(runId, nodeRunId, "cluster_intent", Map.of(
                    "eventType", "streaming",
                    "streamKind", "final_answer",
                    "deltaContent", "",
                    "accumulatedContent", accumulated.toString()
                ));
            }

            @Override
            public void onFailed(String code, String message) {
                emit(runId, nodeRunId, "cluster_intent", Map.of(
                    "eventType", "failed",
                    "errorCode", code,
                    "errorMessage", message
                ));
            }
        };
    }

    /**
     * 仅当节点仍在运行时写入子智能体行：防止用户中断清理数据后，迟到的执行线程把旧数据复活。
     */
    private void saveClusterRowIfNodeRunning(UUID nodeRunId, WorkflowClusterAgentRunEntity row) {
        WorkflowNodeRunEntity node = workflowNodeRunRepository.findById(nodeRunId).orElse(null);
        if (node == null || !"running".equals(node.getState())) {
            return;
        }
        clusterAgentRunRepository.save(row);
    }

    // ------------------------------------------------------------------
    // 失败、重试与终态处理
    // ------------------------------------------------------------------

    private void handleExecutionFailure(NodeExecuteCommand command, String errorCode, String errorMessage) {
        UUID runId = command.runId();
        if ("RUN_CANCELLED".equals(errorCode)) {
            // 用户主动中断：节点清理与 SSE 收尾由 interrupt 接口完成，这里只终态化作业。
            log.info("节点执行已被用户中断 runId={} jobId={} requestId={}", runId, command.jobId(), command.requestId());
            finalizeJobCanceled(command.jobId());
            return;
        }

        boolean retryable = RETRYABLE_ERROR_CODES.contains(errorCode)
            && command.attempt() < properties.getExecution().getMaxAttempts()
            && !cancellationGuard.isCancelled(runId);
        if (retryable) {
            requeueForRetry(command, errorCode, errorMessage);
            return;
        }

        if (!isJobStillActive(command.jobId())) {
            log.info(
                "作业已失效，跳过失败落库 tenantId={} runId={} nodeRunId={} jobId={} errorCode={} requestId={}",
                command.tenantId(), runId, command.nodeRunId(), command.jobId(), errorCode, command.requestId()
            );
            return;
        }
        log.warn(
            "节点执行失败 tenantId={} runId={} nodeRunId={} jobId={} errorCode={} attempt={} requestId={}",
            command.tenantId(), runId, command.nodeRunId(), command.jobId(), errorCode, command.attempt(), command.requestId()
        );
        jobRepository.findById(command.jobId()).ifPresent(job -> {
            if (!job.isTerminal()) {
                job.markFailed(errorCode, errorMessage, clock.instant());
                jobRepository.save(job);
            }
        });
        workbenchRuntimeService.failNodeIfActive(runId, command.nodeRunId(), errorCode, errorMessage);
        emit(runId, command.nodeRunId(), "node_failed", Map.of(
            "errorCode", errorCode,
            "errorMessage", errorMessage == null ? "节点执行失败" : errorMessage
        ));
        streamWriter.append(runId, "message", "[DONE]");
    }

    /**
     * 模型瞬时错误自动重试：当前作业标记失败并立即入队新 attempt。
     * 集群节点已成功的子智能体结果已落库，重试只会重跑失败/未完成部分。
     */
    private void requeueForRetry(NodeExecuteCommand command, String errorCode, String errorMessage) {
        Instant now = clock.instant();
        jobRepository.findById(command.jobId()).ifPresent(job -> {
            if (!job.isTerminal()) {
                job.markFailed(errorCode, errorMessage + "（已自动重试）", now);
                jobRepository.save(job);
            }
        });
        int nextAttempt = command.attempt() + 1;
        WorkflowRunExecutionJobEntity retryJob = WorkflowRunExecutionJobEntity.queued(
            command.tenantId(),
            command.runId(),
            command.nodeRunId(),
            nextAttempt,
            command.operatorUserId(),
            command.requestId(),
            now.plus(Duration.ofSeconds(properties.getExecution().getNodeTimeoutSeconds())),
            now
        );
        jobRepository.save(retryJob);
        emit(command.runId(), command.nodeRunId(), "agent_thinking", Map.of(
            "phase", "model_calling",
            "message", "模型调用出现瞬时故障，正在自动重试（第 " + nextAttempt + " 次尝试）..."
        ));
        commandPublisher.publish(NodeExecuteCommand.of(
            retryJob.getId(),
            command.tenantId(),
            command.runId(),
            command.nodeRunId(),
            command.nodeType(),
            command.operatorUserId(),
            command.requestId(),
            nextAttempt,
            now
        ));
        log.info(
            "节点执行瞬时失败，已自动重试 runId={} nodeRunId={} errorCode={} nextAttempt={} requestId={}",
            command.runId(), command.nodeRunId(), errorCode, nextAttempt, command.requestId()
        );
    }

    private void emitPostCompletionState(UUID runId, UUID nodeRunId) {
        List<WorkflowNodeRunEntity> allNodes = workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(runId);
        WorkflowNodeRunEntity finishedNode = allNodes.stream()
            .filter(node -> node.getId().equals(nodeRunId))
            .findFirst()
            .orElse(null);
        if (finishedNode == null) {
            return;
        }
        // 智能体/集群/交付完成后停在当前节点等待用户确认，与 saveNodeSuccess 的状态机口径一致。
        emit(runId, null, "run_paused", Map.of(
            "nextNodeRunId", finishedNode.getId().toString(),
            "nextNodeName", finishedNode.getName(),
            "nextNodeType", finishedNode.getNodeType(),
            "reason", "等待用户确认后再执行下一步"
        ));
    }

    private void finalizeJobSucceeded(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (!job.isTerminal()) {
                job.markSucceeded(clock.instant());
                jobRepository.save(job);
            }
        });
    }

    private void finalizeJobCanceled(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (!job.isTerminal()) {
                job.markCanceled(clock.instant());
                jobRepository.save(job);
            }
        });
    }

    /**
     * 作业是否仍为当前 Worker 可写入的有效 running 作业。
     * 中断/restart 会把旧 job 标为 canceled，迟到的 Worker 不得据此覆盖新执行结果。
     */
    private boolean isJobStillActive(UUID jobId) {
        return jobRepository.findById(jobId)
            .map(job -> WorkflowRunExecutionJobEntity.STATUS_RUNNING.equals(job.getStatus()))
            .orElse(false);
    }

    private void emitHeartbeat(UUID runId, UUID nodeRunId, String workerId) {
        try {
            leaseService.renew(runId, workerId);
            emit(runId, nodeRunId, "heartbeat", Map.of("workerId", workerId));
        } catch (Exception exception) {
            log.warn("执行心跳发送失败 runId={} workerId={}", runId, workerId, exception);
        }
    }

    private Map<String, Object> variablesBeforeNode(WorkflowRunEntity run, int sortOrder) {
        Map<String, Object> variables = new LinkedHashMap<>(WorkflowRuntimeSystemVariables.from(run, clock));
        for (WorkflowNodeRunEntity node : workflowNodeRunRepository.findByRunIdOrderBySortOrderAsc(run.getId())) {
            if ("completed".equals(node.getState()) && node.getSortOrder() < sortOrder) {
                variables.putAll(node.getOutputSnapshot());
            }
        }
        return variables;
    }

    private void emit(UUID runId, UUID nodeRunId, String eventName, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId.toString());
        if (nodeRunId != null) {
            payload.put("nodeRunId", nodeRunId.toString());
        }
        payload.put("timestamp", clock.instant().toString());
        if (extra != null) {
            payload.putAll(extra);
        }
        streamWriter.append(runId, eventName, payload);
    }

    private static boolean isExecutableState(String state) {
        return "running".equals(state) || "pending".equals(state);
    }

    private static ApiException unwrapAbortException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApiException apiException && ABORT_ERROR_CODES.contains(apiException.getCode())) {
                return apiException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String clusterAgentDisplayText(Map<String, Object> output) {
        String finalAnswer = stringValue(output.get("final_answer"), "");
        if (!finalAnswer.isBlank()) {
            return finalAnswer;
        }
        return stringValue(output.get("summary"), "已完成");
    }

    private static Map<String, Object> intentRoutingSummary(ClusterIntentRoutingSupport.IntentDecision decision) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestedCodes", decision.requestedCodes());
        summary.put("selectedCodes", decision.selectedCodes());
        summary.put("selectedAgentIndexes", decision.selectedAgentIndexes());
        summary.put("reason", decision.reason());
        summary.put("slots", decision.slots());
        summary.put("usedFallback", decision.usedFallback());
        summary.put("fallbackMode", decision.fallbackMode());
        return summary;
    }

    private static Map<String, Object> fixedIntentReplyResult(
        Map<String, Object> variables,
        Map<String, Object> nodeConfig,
        ClusterIntentRoutingSupport.IntentDecision decision
    ) {
        Map<String, Object> result = new LinkedHashMap<>(variables);
        result.put("final_answer", decision.fixedReply());
        result.put("agent_response", decision.fixedReply());
        result.put(ClusterOutputSupport.outputVariable(nodeConfig), decision.fixedReply());
        result.put(ClusterIntentRoutingSupport.DEFAULT_INTENT_OUTPUT_VARIABLE, decision.fixedReply());
        result.put("intentRouting", intentRoutingSummary(decision));
        result.put("summary", "意图分派已按其他情况返回固定话术。");
        return result;
    }

    private static String stringValue(Object value, String fallback) {
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static String summarizeText(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "智能体已完成模型调用。";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    private static String toolResultForEvent(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.length() > 4000 ? normalized.substring(0, 4000) + "\n\n[工具结果已截断]" : normalized;
    }

    private record ClusterAgentSlot(int index, String name, Map<String, Object> config) {
    }

    private record IntentRoutingResult(
        ClusterIntentRoutingSupport.IntentDecision decision,
        List<ClusterAgentSlot> selectedSlots
    ) {
    }

    private record ClusterAgentOutcome(
        int index,
        String name,
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String errorMessage
    ) {

        static ClusterAgentOutcome success(int index, String name, Map<String, Object> output) {
            return new ClusterAgentOutcome(index, name, true, output, null, null);
        }

        static ClusterAgentOutcome failure(int index, String name, String errorCode, String errorMessage) {
            return new ClusterAgentOutcome(index, name, false, Map.of(), errorCode, errorMessage);
        }
    }
}
