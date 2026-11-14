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

/**
 * 通用智能体平台标准内置工具友好名称映射表（仅限平台通用内置能力）
 */
const COMMON_BUILTIN_TOOL_TITLES: Record<string, string> = {
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
  mcp_dataset_query: "查询已保存数据集",
};

/**
 * 提取友好工具展示名称：
 * 通用清洗逻辑：去除系统生成的内部 Hash 与序号，保持平台完全中立与通用，绝不耦合任何特定行业字典。
 */
export function formatFriendlyToolName(rawTitle: string): string {
  if (!rawTitle) return "工具调用";
  const trimmed = rawTitle.trim();

  // 针对形如 "读取 Skill：xxx" 或 "调用 MCP：xxx"
  if (trimmed.startsWith("读取 Skill：") || trimmed.startsWith("调用 MCP：")) {
    return trimmed;
  }

  // 去除前缀 mcp_cap_..._序号 或 cap_...
  const cleaned = trimmed
    .replace(/^mcp_cap_[a-f0-9]+_/i, "")
    .replace(/^cap_[a-f0-9_]+\./i, "")
    .replace(/_\d+$/, "");

  // 匹配平台通用内置工具名称
  if (COMMON_BUILTIN_TOOL_TITLES[cleaned]) {
    return COMMON_BUILTIN_TOOL_TITLES[cleaned];
  }

  // 若本身已包含中文业务名，直接原样使用
  if (/[\u4e00-\u9fa5]/.test(cleaned)) {
    return cleaned;
  }

  // 纯英文标识符下划线转空格首字母大写
  return cleaned.replace(/_/g, " ");
}

/**
 * 根据步骤类型获取对应的通用语义图标、分类标签和色调。
 * 完全遵循通用标准规范（kind / toolType），杜绝根据行业业务关键词（如贷款/明细/报表等）猜测图标。
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

  const defaultBadge = step.toolType === "skill" ? "Skill" : "MCP";
  const rawName = (step.title || "").toLowerCase();

  // 1. MCP 工具统一规范：标准数据与服务接口统一使用 Database 稳定图标与数据蓝标签
  if (step.toolType === "mcp" || rawName.includes("mcp") || rawName.startsWith("调用 mcp")) {
    return {
      Icon: Database,
      label: "数据服务",
      badgeLabel: "MCP",
      tone: "data",
    };
  }

  // 2. Skill 工具统一规范：技能文档与参考资料统一使用 FileText 文档图标
  if (step.toolType === "skill" || rawName.includes("skill") || rawName.startsWith("读取 skill")) {
    return {
      Icon: FileText,
      label: "参考资料",
      badgeLabel: "Skill",
      tone: "knowledge",
    };
  }

  // 3. 联网与检索工具
  if (/web|browser|http|fetch|url|search|grep|retriev|find|联网|网页|检索|搜索/.test(rawName)) {
    return {
      Icon: Globe,
      label: "联网检索",
      badgeLabel: defaultBadge,
      tone: "knowledge",
    };
  }

  // 4. 代码执行与脚本工具
  if (/code|script|shell|exec|eval|python|bash/.test(rawName)) {
    return {
      Icon: Code2,
      label: "执行工具",
      badgeLabel: defaultBadge,
      tone: "code",
    };
  }

  // 5. 通用默认工具
  return {
    Icon: Wrench,
    label: "工具调用",
    badgeLabel: defaultBadge,
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
      // 1. rowCount 提取（金融明细与数据集取数核心场景）：使用不间断空格杜绝折行
      if (typeof data.rowCount === "number") {
        return `共\u00A0${data.rowCount.toLocaleString("zh-CN")}\u00A0条明细`;
      }
      if (typeof data.matchedCount === "number") {
        return `匹配\u00A0${data.matchedCount.toLocaleString("zh-CN")}\u00A0条记录`;
      }
      // 2. 数组长度（如 results, items, documents, rows）
      const list = data.results || data.items || data.documents || data.rows || data.chunks;
      if (Array.isArray(list)) {
        return `获取\u00A0${list.length}\u00A0条记录`;
      }
      // 3. 分块数
      if (typeof data.total_chunks === "number") {
        const fetched = typeof data.fetched_chunks === "number" ? `${data.fetched_chunks} / ` : "";
        return `读取\u00A0${fetched}${data.total_chunks}\u00A0个分块`;
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
      return `共\u00A0${Number(rowMatch[1]).toLocaleString("zh-CN")}\u00A0条明细`;
    }
  }

  return null;
}
