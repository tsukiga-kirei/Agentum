import { useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Segmented, Spin } from "antd";
import { Eye, ClipboardList, Database, FileText } from "lucide-react";
import { SurfacePageLayout } from "../../components/workbench/SurfacePageLayout";
import { useFlipText } from "../../motion/useFlipText";
import { paths } from "../../routes/paths";
import { ExecutionAuditTab } from "./ExecutionAuditTab";
import { ToolAuditTab } from "./ToolAuditTab";
import { OperationLogsTab } from "./OperationLogsTab";

type AuditTabKey = "runs" | "tools" | "operations";

interface AuditTabInfo {
  key: AuditTabKey;
  label: string;
  description: string;
  icon: typeof ClipboardList;
}

const auditTabs: AuditTabInfo[] = [
  { key: "runs", label: "运行审计", description: "追溯全链路工作流运行实例的只读数据轨迹", icon: ClipboardList },
  { key: "tools", label: "工具审计", description: "审查所有外部 MCP 工具、Skill 与模型调用的台账及脱敏载荷", icon: Database },
  { key: "operations", label: "操作日志", description: "记录流程定义变动、发布与权限角色调整的操作记录", icon: FileText },
];

export function AuditPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  // 根据当前路径自动解析活跃 Tab
  const activeTab = useMemo<AuditTabKey>(() => {
    const pathname = location.pathname;
    if (pathname.includes("/operations")) return "operations";
    if (pathname.includes("/tools")) return "tools";
    return "runs";
  }, [location.pathname]);

  const activeTabInfo = useMemo(() => {
    return auditTabs.find((t) => t.key === activeTab) || auditTabs[0];
  }, [activeTab]);
  const moduleDescRef = useRef<HTMLDivElement>(null);
  useFlipText(moduleDescRef, activeTab);

  const moduleSegmentedOptions = useMemo(() => {
    return auditTabs.map((t) => {
      const Icon = t.icon;
      return {
        value: t.key,
        label: (
          <span className="login-portal-option">
            <Icon className="login-portal-option-icon" aria-hidden="true" />
            <span>{t.label}</span>
          </span>
        ),
      };
    });
  }, []);

  const handleTabChange = (key: string) => {
    const tabKey = key as AuditTabKey;
    if (tabKey === "runs") {
      navigate(paths.audit.runs);
    } else if (tabKey === "tools") {
      navigate(paths.audit.tools);
    } else if (tabKey === "operations") {
      navigate(paths.audit.operations);
    }
  };

  return (
    <SurfacePageLayout
      markClassName="audit-page-mark"
      icon={Eye}
      title="运行审计"
      badge="合规证据"
      description="只读性安全追溯底盘。多维度审查大模型推理、外部 MCP 网关调用、交付数据链路及全局管理员操作流水。"
    >
      <div className="system-mgmt-module-switch mb-5">
        <div className="system-mgmt-segmented-scroll">
          <Segmented<AuditTabKey>
            aria-label="审计子页签"
            value={activeTab}
            onChange={handleTabChange}
            options={moduleSegmentedOptions}
            className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented"
          />
        </div>
        <div ref={moduleDescRef} className="login-portal-description login-portal-description--business">
          <span className="login-portal-description-dot" />
          {activeTabInfo.description}
        </div>
      </div>

      <Spin spinning={loading}>
        <div className="sys-fade-in">
          {activeTab === "runs" && <ExecutionAuditTab setLoading={setLoading} />}
          {activeTab === "tools" && <ToolAuditTab setLoading={setLoading} />}
          {activeTab === "operations" && <OperationLogsTab setLoading={setLoading} />}
        </div>
      </Spin>
    </SurfacePageLayout>
  );
}
export default AuditPage;
