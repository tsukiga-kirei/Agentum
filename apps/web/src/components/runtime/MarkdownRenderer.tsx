import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface MarkdownRendererProps {
  content: string;
  className?: string;
  compact?: boolean;
}

/** 补全标题 # 后缺空格的情况，避免中文用户输入 ###标题 无法被解析。 */
export function normalizeMarkdownHeadings(content: string): string {
  return content.replace(/^(#{1,6})([^\s#\n])/gm, "$1 $2");
}

export function MarkdownRenderer({ content, className = "", compact = false }: MarkdownRendererProps) {
  const normalizedContent = normalizeMarkdownHeadings(content || "");

  return (
    <div className={`agent-markdown ${compact ? "agent-markdown--compact" : ""} ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noreferrer">
              {children}
            </a>
          ),
        }}
      >
        {normalizedContent}
      </ReactMarkdown>
    </div>
  );
}
