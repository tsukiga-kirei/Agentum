const DESIGN_STATUS_META: Record<string, { label: string; className: string }> = {
  active: { label: "未下线", className: "sys-status--active" },
  all: { label: "全部状态", className: "sys-status--neutral" },
  draft: { label: "草稿", className: "sys-status--paused" },
  published: { label: "已发布", className: "sys-status--success" },
  review: { label: "待校验", className: "sys-status--running" },
};

export function WorkflowDesignStatusBadge({ status }: { status: string }) {
  const meta = DESIGN_STATUS_META[status] ?? {
    label: status || "未知状态",
    className: "sys-status--neutral",
  };

  return (
    <span className={`sys-status ${meta.className}`}>
      <span className="sys-status-dot" aria-hidden="true" />
      {meta.label}
    </span>
  );
}
