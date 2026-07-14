import { useCallback, useEffect, useMemo, useState } from "react";
import type { LucideIcon } from "lucide-react";
import { DatePicker, Drawer, Empty, Pagination, Select, message } from "antd";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import {
  Activity,
  AlertTriangle,
  Ban,
  CalendarClock,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Edit3,
  Eye,
  FileText,
  History,
  Loader2,
  PauseCircle,
  PlayCircle,
  Search,
  Settings2,
  Trash2,
  Workflow,
  X,
  Zap,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { CronExpressionGenerator } from "../../components/cron/CronExpressionGenerator";
import { AgentumApiError, workbenchApi } from "../../services/apiClient";
import { useAuthStore } from "../../stores/authStore";
import { paths } from "../../routes/paths";
import { getThemedDrawerRootClassName } from "../../utils/theme";
import type {
  WorkbenchAvailableWorkflowRow,
  WorkflowScheduleExecutionRow,
  WorkflowScheduleInputField,
  WorkflowScheduleRow,
} from "../../types/workbench";

const SCHEDULE_PAGE_SIZE = 10;
const WORKFLOW_SELECT_SIZE = 80;

const statusOptions = [
  { value: "all", label: "全部状态" },
  { value: "active", label: "启用中" },
  { value: "paused", label: "已暂停" },
] as const;

const cronPresets = [
  { key: "hourly", label: "每小时", cron: "0 0 * * * *" },
  { key: "daily_9", label: "每天 09:00", cron: "0 0 9 * * *" },
  { key: "weekly_mon_9", label: "每周一 09:00", cron: "0 0 9 * * MON" },
  { key: "monthly_1_9", label: "每月 1 日 09:00", cron: "0 0 9 1 * *" },
  { key: "custom", label: "自定义 cron", cron: "" },
] as const;

type ScheduleFormState = {
  mode: "create" | "edit";
  scheduleId: string | null;
  workflowId: string;
  name: string;
  presetKey: string;
  presetLabel: string;
  cronExpression: string;
  status: "active" | "paused";
  inputPayload: Record<string, unknown>;
};

const defaultForm: ScheduleFormState = {
  mode: "create",
  scheduleId: null,
  workflowId: "",
  name: "",
  presetKey: "daily_9",
  presetLabel: "每天 09:00",
  cronExpression: "0 0 9 * * *",
  status: "active",
  inputPayload: {},
};

const selectClassNames = { popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } };
const selectSuffixIcon = <ChevronDown className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />;

dayjs.locale("zh-cn");

export function WorkflowSchedulesPanel() {
  const navigate = useNavigate();
  const themeMode = useAuthStore((state) => state.themeMode);
  const token = useAuthStore((state) => state.token) || "";
  const tenantId = useAuthStore((state) => state.user?.tenantId) || "";
  const drawerRootClassName = getThemedDrawerRootClassName(themeMode);
  const [messageApi, messageContextHolder] = message.useMessage();

  const [keywordDraft, setKeywordDraft] = useState("");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<(typeof statusOptions)[number]["value"]>("all");
  const [page, setPage] = useState(1);
  const [schedules, setSchedules] = useState<WorkflowScheduleRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [workflows, setWorkflows] = useState<WorkbenchAvailableWorkflowRow[]>([]);
  const [workflowLoading, setWorkflowLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form, setForm] = useState<ScheduleFormState>(defaultForm);
  const [inputFields, setInputFields] = useState<WorkflowScheduleInputField[]>([]);
  const [inputLoading, setInputLoading] = useState(false);
  const [executions, setExecutions] = useState<WorkflowScheduleExecutionRow[]>([]);
  const [executionsLoading, setExecutionsLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [triggeringScheduleId, setTriggeringScheduleId] = useState<string | null>(null);
  const [cronGeneratorOpen, setCronGeneratorOpen] = useState(false);

  const workflowOptions = useMemo(
    () => workflows.filter((workflow) => workflow.canLaunch).map((workflow) => ({
      value: workflow.id,
      label: `${workflow.name} · v${workflow.latestVersionNumber}`,
    })),
    [workflows],
  );

  const loadSchedules = useCallback(async () => {
    if (!tenantId || !token) {
      setSchedules([]);
      setTotal(0);
      return;
    }
    setLoading(true);
    try {
      const data = await workbenchApi.listSchedules(
        tenantId,
        token,
        keyword,
        status === "all" ? "" : status,
        page,
        SCHEDULE_PAGE_SIZE,
      );
      setSchedules(data.items);
      setTotal(data.total);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "定时任务加载失败";
      console.warn("[workbench] 定时任务加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
      setSchedules([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, messageApi, page, status, tenantId, token]);

  const loadWorkflows = useCallback(async () => {
    if (!tenantId || !token) {
      return;
    }
    setWorkflowLoading(true);
    try {
      const data = await workbenchApi.listAvailableWorkflows(tenantId, token, "", 1, WORKFLOW_SELECT_SIZE);
      setWorkflows(data.items);
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "可发起流程加载失败";
      messageApi.error(reason);
    } finally {
      setWorkflowLoading(false);
    }
  }, [messageApi, tenantId, token]);

  const loadInputFields = useCallback(async (workflowId: string, payload?: Record<string, unknown>) => {
    if (!tenantId || !token || !workflowId) {
      setInputFields([]);
      return;
    }
    setInputLoading(true);
    try {
      const data = await workbenchApi.getScheduleInputFields(tenantId, token, workflowId);
      setInputFields(data.inputFields);
      setForm((current) => {
        const nextPayload = { ...(payload ?? current.inputPayload) };
        data.inputFields.forEach((field) => {
          if (!(field.variable in nextPayload)) {
            nextPayload[field.variable] = field.defaultValueSource === "fixed" ? field.defaultValue : "";
          }
        });
        return { ...current, inputPayload: nextPayload };
      });
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "输入字段加载失败";
      messageApi.error(reason);
      setInputFields([]);
    } finally {
      setInputLoading(false);
    }
  }, [messageApi, tenantId, token]);

  const loadExecutions = useCallback(async (scheduleId: string) => {
    if (!tenantId || !token) {
      return;
    }
    setExecutionsLoading(true);
    try {
      const data = await workbenchApi.listScheduleExecutions(tenantId, token, scheduleId, 1, 6);
      setExecutions(data.items);
    } catch (error) {
      console.warn("[workbench] 定时任务执行记录加载失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      setExecutions([]);
    } finally {
      setExecutionsLoading(false);
    }
  }, [tenantId, token]);

  useEffect(() => {
    void loadSchedules();
  }, [loadSchedules]);

  useEffect(() => {
    if (drawerOpen) {
      void loadWorkflows();
    }
  }, [drawerOpen, loadWorkflows]);

  function openCreateDrawer() {
    setForm(defaultForm);
    setInputFields([]);
    setExecutions([]);
    setDrawerOpen(true);
  }

  function openEditDrawer(schedule: WorkflowScheduleRow) {
    const matchedPreset = cronPresets.find((preset) => preset.cron === schedule.cronExpression);
    setForm({
      mode: "edit",
      scheduleId: schedule.id,
      workflowId: schedule.workflowId,
      name: schedule.name,
      presetKey: matchedPreset?.key ?? "custom",
      presetLabel: matchedPreset?.label ?? schedule.shortcutLabel ?? "自定义 cron",
      cronExpression: schedule.cronExpression,
      status: schedule.status === "paused" ? "paused" : "active",
      inputPayload: { ...schedule.inputPayload },
    });
    setDrawerOpen(true);
    void loadInputFields(schedule.workflowId, schedule.inputPayload);
    void loadExecutions(schedule.id);
  }

  function handlePresetChange(value: string) {
    const preset = cronPresets.find((item) => item.key === value) ?? cronPresets[0];
    setForm((current) => ({
      ...current,
      presetKey: preset.key,
      presetLabel: preset.label,
      cronExpression: preset.cron || current.cronExpression,
    }));
  }

  async function submitSchedule() {
    if (!tenantId || !token) {
      return;
    }
    if (!form.workflowId) {
      messageApi.warning("请选择要自动执行的流程");
      return;
    }
    if (!form.name.trim()) {
      messageApi.warning("请输入定时任务名称");
      return;
    }
    const missingField = inputFields.find((field) =>
      field.required
      && field.defaultValueSource !== "system"
      && !String(form.inputPayload[field.variable] ?? "").trim(),
    );
    if (missingField) {
      messageApi.warning(`请配置输入字段「${missingField.label}」`);
      return;
    }
    setSubmitting(true);
    try {
      const body = {
        workflowId: form.workflowId,
        name: form.name.trim(),
        cronExpression: form.cronExpression.trim(),
        shortcutKey: form.presetKey,
        shortcutLabel: form.presetLabel,
        status: form.status,
        inputPayload: form.inputPayload,
      };
      if (form.mode === "edit" && form.scheduleId) {
        await workbenchApi.updateSchedule(tenantId, token, form.scheduleId, body);
        messageApi.success("定时任务已更新");
      } else {
        await workbenchApi.createSchedule(tenantId, token, body);
        messageApi.success("定时任务已创建");
      }
      setDrawerOpen(false);
      void loadSchedules();
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "定时任务保存失败";
      messageApi.error(reason);
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleSchedule(schedule: WorkflowScheduleRow) {
    if (!tenantId || !token) {
      return;
    }
    const nextStatus = schedule.status === "active" ? "paused" : "active";
    try {
      await workbenchApi.updateScheduleStatus(tenantId, token, schedule.id, nextStatus);
      messageApi.success(nextStatus === "active" ? "定时任务已启用" : "定时任务已暂停");
      void loadSchedules();
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "状态调整失败";
      messageApi.error(reason);
    }
  }

  async function deleteSchedule(schedule: WorkflowScheduleRow) {
    if (!tenantId || !token) {
      return;
    }
    try {
      await workbenchApi.deleteSchedule(tenantId, token, schedule.id);
      messageApi.success("定时任务已删除");
      void loadSchedules();
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "定时任务删除失败";
      messageApi.error(reason);
    }
  }

  async function triggerSchedule(schedule: WorkflowScheduleRow) {
    if (!tenantId || !token) {
      return;
    }
    setTriggeringScheduleId(schedule.id);
    try {
      const result = await workbenchApi.triggerSchedule(tenantId, token, schedule.id);
      messageApi.success("定时任务已开始执行");
      void loadSchedules();
      if (drawerOpen && form.scheduleId === schedule.id) {
        void loadExecutions(schedule.id);
      }
      if (result.runId) {
        navigate(paths.workbench.run(result.runId));
      }
    } catch (error) {
      const reason = error instanceof AgentumApiError ? error.message : "定时任务执行失败";
      console.warn("[workbench] 定时任务手动执行失败", { code: error instanceof AgentumApiError ? error.code : "unknown" });
      messageApi.error(reason);
    } finally {
      setTriggeringScheduleId(null);
    }
  }

  return (
    <section className="workbench-task-center sys-fade-in" aria-label="定时任务">
      {messageContextHolder}
      <div className="workflow-library-toolbar">
        <div className="workflow-library-toolbar-actions">
          <label className="workflow-definition-search">
            <Search className="h-[18px] w-[18px]" aria-hidden="true" />
            <span className="sr-only">搜索定时任务</span>
            <input
              value={keywordDraft}
              onChange={(event) => setKeywordDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  setKeyword(keywordDraft.trim());
                  setPage(1);
                }
              }}
              placeholder="按定时任务或流程名称搜索"
            />
          </label>
          <Select
            className="agent-admin-select workbench-task-center-state-select"
            classNames={selectClassNames}
            prefix={<Activity className="h-[18px] w-[18px] text-[var(--color-text-tertiary)]" aria-hidden="true" />}
            suffixIcon={selectSuffixIcon}
            value={status}
            options={statusOptions.map((option) => ({ value: option.value, label: option.label }))}
            onChange={(value) => {
              setStatus(value);
              setPage(1);
            }}
          />
          <button type="button" className="sys-btn sys-btn--default" onClick={() => { setKeyword(keywordDraft.trim()); setPage(1); }}>
            <Search size={18} aria-hidden="true" />
            查询
          </button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={openCreateDrawer}>
            <CalendarClock size={18} aria-hidden="true" />
            新建定时任务
          </button>
        </div>
      </div>

      {loading ? (
        <div className="workflow-definition-empty-state">
          <Loader2 className="h-8 w-8 animate-spin" aria-hidden="true" />
          <p>正在加载定时任务</p>
        </div>
      ) : schedules.length === 0 ? (
        <div className="workflow-definition-empty-state">
          <CalendarClock className="h-8 w-8" aria-hidden="true" />
          <p>{keyword ? "当前暂无匹配的定时任务" : "暂无定时任务"}</p>
          <span>{keyword ? "可以调整搜索词后重试。" : "新建后，系统会按配置自动执行你有权限的流程。"}</span>
        </div>
      ) : (
        <div className="workbench-task-center-list">
          {schedules.map((schedule, index) => (
            <ScheduleListItem
              key={schedule.id}
              schedule={schedule}
              index={index}
              triggering={triggeringScheduleId === schedule.id}
              onOpen={() => openEditDrawer(schedule)}
              onToggle={() => void toggleSchedule(schedule)}
              onTrigger={() => void triggerSchedule(schedule)}
              onDelete={() => void deleteSchedule(schedule)}
              onOpenRun={() => schedule.lastRunId && navigate(paths.workbench.run(schedule.lastRunId))}
            />
          ))}
        </div>
      )}

      {total > 0 ? (
        <div className="agent-admin-pagination-wrap mt-4 px-0 py-4">
          <Pagination
            className="agent-admin-pagination"
            current={page}
            total={total}
            pageSize={SCHEDULE_PAGE_SIZE}
            showSizeChanger={false}
            showTotal={(count, range) => `当前 ${range[0]}-${range[1]} 条，共 ${count} 条`}
            onChange={setPage}
          />
        </div>
      ) : null}

      <Drawer
        title={form.mode === "edit" ? "编辑定时任务" : "新建定时任务"}
        width={560}
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setCronGeneratorOpen(false);
        }}
        rootClassName={drawerRootClassName}
      >
        <div className="sys-drawer-section schedule-drawer">
          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">自动执行流程</label>
            <Select
              className="agent-admin-select w-full"
              classNames={selectClassNames}
              suffixIcon={selectSuffixIcon}
              loading={workflowLoading}
              disabled={form.mode === "edit"}
              value={form.workflowId || undefined}
              options={workflowOptions}
              placeholder="选择你有权限发起的流程"
              onChange={(workflowId) => {
                const selected = workflows.find((workflow) => workflow.id === workflowId);
                setForm((current) => ({
                  ...current,
                  workflowId,
                  name: current.name || (selected ? `${selected.name}定时执行` : current.name),
                  inputPayload: {},
                }));
                void loadInputFields(workflowId, {});
              }}
            />
          </div>

          <div className="sys-field">
            <label className="sys-field-label sys-field-label--required">任务名称</label>
            <div className="sys-field-input-wrap">
              <FileText size={16} className="sys-field-prefix" aria-hidden="true" />
              <input
                className="sys-field-input"
                value={form.name}
                maxLength={160}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
              />
            </div>
          </div>

          <div className="sys-field-row schedule-cron-row">
            <div className="sys-field">
              <label className="sys-field-label">快捷定时</label>
              <Select
                className="agent-admin-select w-full"
                classNames={selectClassNames}
                suffixIcon={selectSuffixIcon}
                value={form.presetKey}
                options={cronPresets.map((preset) => ({ value: preset.key, label: preset.label }))}
                onChange={handlePresetChange}
              />
              <button
                type="button"
                className="sys-btn sys-btn--text sys-btn--sm schedule-cron-generator-toggle"
                onClick={() => setCronGeneratorOpen((open) => !open)}
              >
                <Settings2 size={14} aria-hidden="true" />
                {cronGeneratorOpen ? "收起生成器" : "打开 Cron 生成器"}
              </button>
            </div>
            <div className="sys-field">
              <label className="sys-field-label sys-field-label--required">cron 表达式</label>
              <div className="sys-field-input-wrap">
                <Clock3 size={16} className="sys-field-prefix" aria-hidden="true" />
                <input
                  className="sys-field-input"
                  value={form.cronExpression}
                  maxLength={120}
                  onChange={(event) => setForm((current) => ({ ...current, cronExpression: event.target.value, presetKey: "custom", presetLabel: "自定义 cron" }))}
                />
              </div>
            </div>
          </div>
          {cronGeneratorOpen ? (
            <div className="schedule-cron-generator">
              <CronExpressionGenerator
                value={form.cronExpression}
                onChange={(cronExpression) => setForm((current) => ({
                  ...current,
                  cronExpression,
                  presetKey: "custom",
                  presetLabel: "自定义 cron",
                }))}
              />
            </div>
          ) : null}

          {inputLoading ? (
            <div className="workflow-drawer-loading">
              <Loader2 size={16} className="animate-spin" aria-hidden="true" />
              正在读取输入字段
            </div>
          ) : inputFields.length > 0 ? (
            <div className="sys-config-group">
              <div className="sys-config-group-title">输入节点预置值</div>
              {inputFields.map((field) => (
                <div className="sys-field" key={`${field.nodeId}-${field.variable}`}>
                  <label className={`sys-field-label ${field.required ? "sys-field-label--required" : ""}`}>
                    {field.label}
                    <span className="agent-muted ml-2 text-[11px]">{field.nodeName}</span>
                  </label>
                  {field.defaultValueSource === "system" ? (
                    <div className="schedule-system-input-value">
                      <CalendarClock size={16} aria-hidden="true" />
                      <span>每次运行自动取“{describeSystemDefaultValue(field.systemDefaultValue)}”</span>
                      <small>{describeSystemDefaultValueExample(field.systemDefaultValue)}</small>
                    </div>
                  ) : (
                    field.fieldType === "date" ? (
                      <DatePicker
                        picker={field.dateGranularity === "year" ? "year" : field.dateGranularity === "month" ? "month" : "date"}
                        format={field.dateGranularity === "year" ? "YYYY年" : field.dateGranularity === "month" ? "YYYY年MM月" : "YYYY年MM月DD日"}
                        value={parseSchedulePickerValue(form.inputPayload[field.variable])}
                        inputReadOnly
                        placeholder={field.dateGranularity === "year" ? "请选择年份" : field.dateGranularity === "month" ? "请选择年月" : "请选择日期"}
                        className="workbench-user-input-date-picker w-full"
                        classNames={{ popup: { root: "workbench-user-input-date-picker-popup" } }}
                        onChange={(value) => setForm((current) => ({
                          ...current,
                          inputPayload: {
                            ...current.inputPayload,
                            [field.variable]: value
                              ? value.format(field.dateGranularity === "year" ? "YYYY" : field.dateGranularity === "month" ? "YYYY-MM" : "YYYY-MM-DD")
                              : "",
                          },
                        }))}
                      />
                    ) : (
                      <div className="sys-field-input-wrap">
                        <Workflow size={16} className="sys-field-prefix" aria-hidden="true" />
                        <input
                          type="text"
                          className="sys-field-input"
                          value={String(form.inputPayload[field.variable] ?? "")}
                          placeholder={field.placeholder || field.variable}
                          onChange={(event) => setForm((current) => ({
                            ...current,
                            inputPayload: { ...current.inputPayload, [field.variable]: event.target.value },
                          }))}
                        />
                      </div>
                    )
                  )}
                </div>
              ))}
            </div>
          ) : form.workflowId ? (
            <div className="workflow-definition-empty-state py-6">
              <FileText className="h-7 w-7" aria-hidden="true" />
              <p>该流程没有需要预置的输入节点</p>
            </div>
          ) : null}

          {form.mode === "edit" ? (
            <div className="sys-config-group">
              <div className="sys-config-group-title">最近执行记录</div>
              {executionsLoading ? (
                <div className="workflow-drawer-loading">
                  <Loader2 size={16} className="animate-spin" aria-hidden="true" />
                  正在加载执行记录
                </div>
              ) : executions.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无执行记录" />
              ) : (
                <div className="schedule-execution-list">
                  {executions.map((execution) => {
                    const presentation = resolveExecutionPresentation(execution);
                    const StatusIcon = presentation.icon;
                    return (
                    <article className={`schedule-execution-item schedule-execution-item--${presentation.tone}`} key={execution.id}>
                      <div className="schedule-execution-item-main">
                        <span className={`schedule-execution-item-icon schedule-execution-item-icon--${presentation.tone}`}>
                          <StatusIcon size={16} className={execution.status === "running" ? "animate-spin" : undefined} aria-hidden="true" />
                        </span>
                        <div className="schedule-execution-item-body">
                          <div className="schedule-execution-item-head">
                            <p className="schedule-execution-item-title">{presentation.title}</p>
                            <span className={`schedule-execution-item-tag schedule-execution-item-tag--${presentation.tone}`}>{presentation.tag}</span>
                          </div>
                          <p className="schedule-execution-item-time">{formatExecutionTime(execution)}</p>
                          <p className="schedule-execution-item-message">{execution.message?.trim() || (execution.status === "running" ? "执行中" : "—")}</p>
                        </div>
                      </div>
                      {execution.runId ? (
                        <div className="schedule-execution-item-actions">
                          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => navigate(paths.workbench.run(execution.runId!))}>
                            <Eye size={13} aria-hidden="true" />
                            查看运行
                          </button>
                        </div>
                      ) : null}
                    </article>
                    );
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>
        <div className="sys-drawer-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={() => setDrawerOpen(false)}>
            <X size={14} />
            取消
          </button>
          <div className="sys-drawer-footer-right">
            <button type="button" className="sys-btn sys-btn--primary" disabled={submitting} onClick={() => void submitSchedule()}>
              {submitting ? <Loader2 size={16} className="animate-spin" aria-hidden="true" /> : <CalendarClock size={16} aria-hidden="true" />}
              保存定时任务
            </button>
          </div>
        </div>
      </Drawer>
    </section>
  );
}

function ScheduleListItem({
  schedule,
  index,
  triggering,
  onOpen,
  onToggle,
  onTrigger,
  onDelete,
  onOpenRun,
}: {
  schedule: WorkflowScheduleRow;
  index: number;
  triggering: boolean;
  onOpen: () => void;
  onToggle: () => void;
  onTrigger: () => void;
  onDelete: () => void;
  onOpenRun: () => void;
}) {
  return (
    <div className="sys-preview-item sys-card-enter" style={{ animationDelay: `${index * 40}ms` }}>
      <div className="sys-preview-item-left">
        <span className="sys-preview-item-icon sys-card-avatar--cap">
          <CalendarClock size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-[var(--color-text-primary)]">{schedule.name}</p>
          <p className="truncate text-xs text-[var(--color-text-secondary)]">{schedule.workflowName} · v{schedule.workflowVersionNumber} · {schedule.shortcutLabel || schedule.cronExpression}</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">
            下次：{formatDate(schedule.nextRunAt)} · 上次：{formatLastRun(schedule)}
          </p>
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${schedule.status === "active" ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300" : "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"}`}>
          {schedule.status === "active" ? "启用中" : "已暂停"}
        </span>
        {schedule.lastRunId ? (
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={onOpenRun}>
            <History size={13} aria-hidden="true" />
            上次运行
          </button>
        ) : null}
        <button type="button" className="agent-button h-7 px-2 text-xs" onClick={onOpen}>
          <Edit3 size={13} aria-hidden="true" />
          编辑
        </button>
        <button
          type="button"
          className="sys-btn sys-btn--default sys-btn--sm"
          disabled={triggering}
          onClick={onTrigger}
        >
          {triggering ? <Loader2 size={13} className="animate-spin" aria-hidden="true" /> : <Zap size={13} aria-hidden="true" />}
          执行
        </button>
        <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={onToggle}>
          {schedule.status === "active" ? (
            <>
              <PauseCircle size={13} aria-hidden="true" />
              暂停
            </>
          ) : (
            <>
              <PlayCircle size={13} aria-hidden="true" />
              启用
            </>
          )}
        </button>
        <button type="button" className="sys-btn sys-btn--danger sys-btn--sm" onClick={onDelete}>
          <Trash2 size={13} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

function formatDate(value?: string | null) {
  if (!value) return "—";
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}

function describeSystemDefaultValue(value: string) {
  if (value === "current_year") return "当前年";
  if (value === "current_month") return "当前年月";
  if (value === "previous_month") return "上个年月";
  return "当前日期";
}

function describeSystemDefaultValueExample(value: string) {
  if (value === "current_year") return "按计划触发年份生成 YYYY";
  return value === "current_date" ? "按计划触发日期生成 YYYY-MM-DD" : "按计划触发月份生成 YYYY-MM";
}

function parseSchedulePickerValue(value: unknown) {
  if (typeof value !== "string" || !value) {
    return null;
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
}

function formatLastRun(schedule: WorkflowScheduleRow) {
  if (!schedule.lastRunAt) {
    return "暂无";
  }
  const state = schedule.lastRunState === "succeeded" ? "成功" : schedule.lastRunState === "aborted" ? "中止" : schedule.lastRunState || "运行中";
  return `${state} · ${formatDate(schedule.lastRunAt)}`;
}

function formatExecutionTime(execution: WorkflowScheduleExecutionRow) {
  const triggerAt = execution.startedAt || execution.scheduledAt;
  if (execution.completedAt && execution.completedAt !== triggerAt) {
    return `触发：${formatDate(triggerAt)} · 结束：${formatDate(execution.completedAt)}`;
  }
  return `触发：${formatDate(triggerAt)}`;
}

type ExecutionTone = "success" | "danger" | "warning" | "info" | "neutral";

function resolveExecutionPresentation(execution: WorkflowScheduleExecutionRow): {
  tone: ExecutionTone;
  tag: string;
  title: string;
  icon: LucideIcon;
} {
  if (execution.status === "succeeded") {
    return { tone: "success", tag: "执行成功", title: "执行成功", icon: CheckCircle2 };
  }
  if (execution.status === "running") {
    return { tone: "info", tag: "执行中", title: "执行中", icon: Loader2 };
  }
  const message = execution.message?.trim() ?? "";
  if (message.includes("已删除")) {
    return { tone: "neutral", tag: "运行已删除", title: "执行中止", icon: Ban };
  }
  if (message.includes("已失效")) {
    return { tone: "warning", tag: "记录失效", title: "执行中止", icon: AlertTriangle };
  }
  if (message.includes("人工")) {
    return { tone: "warning", tag: "待人工处理", title: "执行中止", icon: AlertTriangle };
  }
  if (message.includes("失败") || message.includes("待办")) {
    return { tone: "danger", tag: "失败待办", title: "执行中止", icon: PauseCircle };
  }
  return { tone: "danger", tag: "执行中止", title: "执行中止", icon: PauseCircle };
}
