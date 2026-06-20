const RUN_STATE_META: Record<string, { label: string; className: string }> = {
  all: { label: "全部状态", className: "sys-status--neutral" },
  running: { label: "执行中", className: "sys-status--running" },
  paused: { label: "已暂停", className: "sys-status--paused" },
  completed: { label: "已完成", className: "sys-status--success" },
  failed: { label: "已失败", className: "sys-status--failed" },
  canceled: { label: "已取消", className: "sys-status--canceled" },
};

export function RunStateBadge({ state }: { state: string }) {
  const meta = RUN_STATE_META[state] ?? {
    label: state || "未知状态",
    className: "sys-status--neutral",
  };

  return (
    <span className={`sys-status ${meta.className}`}>
      <span className="sys-status-dot" aria-hidden="true" />
      {meta.label}
    </span>
  );
}
