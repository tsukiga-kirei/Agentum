import type { LucideIcon } from "lucide-react";
import {
  BrainCircuit,
  Code2,
  Database,
  FileText,
  Globe,
  Search,
  Sparkles,
  Wrench,
} from "lucide-react";
import type { AgentExecutionStep } from "../types/runtime-types";

export interface ToolAppearance {
  Icon: LucideIcon;
  label: string;
  badgeLabel: string;
  tone: "thinking" | "knowledge" | "data" | "code" | "tool";
}

/** 常见金融业务报表明细工具中英文友好名称映射表 */
const FINANCIAL_TOOL_TITLES: Record<string, string> = {
  get_financial_work_report_core_kpi: "核心经营指标",
  get_financial_work_report_key_business: "重点业务指标",
  get_financial_work_report_risk_indicators: "监管风险指标",
  get_financial_work_report_service_group_key_business: "服务集团主业横表",
  get_financial_work_report_service_group_key_business_vertical: "服务集团主业纵表",
  get_financial_work_report_loan_details: "信贷明细",
  get_financial_work_report_interbank_details: "同业明细",
  get_financial_work_report_bond_details: "债券明细",
  get_financial_work_report_fund_details: "基金明细",
  get_financial_work_report_deposit_details: "存款明细",
  mcp_dataset_query: "查询已保存数据集",
};

/**
 * 提取友好工具展示名称：
 * 去除 mcp_cap_xxx_、cap_xxx. 等内部生成的 Hash 与序号，优先匹配业务友好中文，否则格式化为可读词组。
 */
export function formatFriendlyToolName(rawTitle: string): string {
  if (!rawTitle) return "工具调用";
  const trimmed = rawTitle.trim();

  // 若已有规范中文业务名，优先使用
  for (const [key, name] of Object.entries(FINANCIAL_TOOL_TITLES)) {
    if (trimmed.includes(key)) {
      return name;
    }
  }

  // 去除前缀 mcp_cap_..._序号 或 cap_...
  let cleaned = trimmed
    .replace(/^mcp_cap_[a-f0-9]+_/i, "")
    .replace(/^cap_[a-f0-9_]+\./i, "")
    .replace(/_\d+$/, "");

  // 再次匹配去除前缀后的名称
  if (FINANCIAL_TOOL_TITLES[cleaned]) {
    return FINANCIAL_TOOL_TITLES[cleaned];
  }

  // 常见通用工具名翻译
  const COMMON_NAMES: Record<string, string> = {
    search_knowledge: "检索知识库",
    knowledge_search: "检索知识库",
    read_document: "读取文档",
    list_documents: "列出文档",
    web_search: "联网检索",
    browser_navigate: "浏览页面",
    grep_chunks: "分块检索",
    shell_exec: "运行命令",
    code_eval: "执行代码",
    final_answer: "提交最终答案",
  };
  if (COMMON_NAMES[cleaned]) {
    return COMMON_NAMES[cleaned];
  }

  // 针对形如 "读取 Skill：xxx" 或 "调用 MCP：xxx"
  if (cleaned.startsWith("读取 Skill：") || cleaned.startsWith("调用 MCP：")) {
    return cleaned;
  }

  // 针对下划线命名转下划线分词大写或普通可读名
  return cleaned;
}

/**
 * 根据步骤类型和工具名称获取对应的语义图标、分类标签和色调
 */
export function getToolSemanticAppearance(step: AgentExecutionStep): ToolAppearance {
  if (step.kind === "reasoning") {
    return {
      Icon: Sparkles,
      label: "深度思考",
      badgeLabel: "思考",
      tone: "thinking",
    };
  }

  const rawName = (step.title || "").toLowerCase();
  const detailStr = (step.detail || "").toLowerCase();

  // 金融明细、数据表、SQL、数据库、数据集取数
  if (
    /loan|interbank|bond|fund|deposit|dataset|sql|query|database|table|明细|报表|存款|贷款|同业|债券|基金/.test(
      rawName + " " + detailStr,
    )
  ) {
    return {
      Icon: Database,
      label: "数据查询",
      badgeLabel: step.toolType === "mcp" ? "MCP 数据" : "数据",
      tone: "data",
    };
  }

  // 联网与浏览器
  if (/web|browser|http|fetch|url|联网|网页/.test(rawName)) {
    return {
      Icon: Globe,
      label: "联网检索",
      badgeLabel: "网络",
      tone: "knowledge",
    };
  }

  // 搜索、检索、切片
  if (/search|grep|retriev|find|检索|搜索/.test(rawName)) {
    return {
      Icon: Search,
      label: "检索资料",
      badgeLabel: "检索",
      tone: "knowledge",
    };
  }

  // 文档处理、Skill 读取
  if (step.toolType === "skill" || /skill|doc|read|chunk|file|pdf|excel|文档|文件/.test(rawName)) {
    return {
      Icon: FileText,
      label: "参考资料",
      badgeLabel: step.toolType === "skill" ? "Skill" : "文档",
      tone: "knowledge",
    };
  }

  // 代码执行与脚本
  if (/code|script|shell|exec|eval|python|bash/.test(rawName)) {
    return {
      Icon: Code2,
      label: "执行工具",
      badgeLabel: "执行",
      tone: "code",
    };
  }

  // 默认工具
  return {
    Icon: Wrench,
    label: "工具调用",
    badgeLabel: step.toolType === "mcp" ? "MCP" : "工具",
    tone: "tool",
  };
}

/**
 * 从步骤 detail 或 summary 中提取轻量结果胶囊摘要（如 “44 条明细”、“读取 3 个分块”）
 */
export function extractResultSummary(step: AgentExecutionStep): string | null {
  if (step.status === "running") {
    return null;
  }

  const detail = step.detail?.trim() || "";
  if (!detail) {
    return null;
  }

  // 尝试解析 JSON 输出
  try {
    const data = JSON.parse(detail);
    if (data && typeof data === "object") {
      // 1. rowCount 提取（金融明细与数据集取数核心场景）
      if (typeof data.rowCount === "number") {
        return `共 ${data.rowCount.toLocaleString("zh-CN")} 条明细`;
      }
      if (typeof data.matchedCount === "number") {
        return `匹配 ${data.matchedCount.toLocaleString("zh-CN")} 条记录`;
      }
      // 2. 数组长度（如 results, items, documents, rows）
      const list = data.results || data.items || data.documents || data.rows || data.chunks;
      if (Array.isArray(list)) {
        return `获取 ${list.length} 条记录`;
      }
      // 3. 分块数
      if (typeof data.total_chunks === "number") {
        const fetched = typeof data.fetched_chunks === "number" ? `${data.fetched_chunks} / ` : "";
        return `读取 ${fetched}${data.total_chunks} 个分块`;
      }
      // 4. 退出码
      if (data.exit_code !== undefined) {
        const code = Number(data.exit_code);
        return code === 0 ? "命令执行成功 (退出码 0)" : `命令异常 (退出码 ${code})`;
      }
    }
  } catch {
    // 非 JSON 格式，尝试正则从文本提取
    const rowMatch = detail.match(/"rowCount"\s*:\s*(\d+)/);
    if (rowMatch) {
      return `共 ${Number(rowMatch[1]).toLocaleString("zh-CN")} 条明细`;
    }
  }

  return null;
}
