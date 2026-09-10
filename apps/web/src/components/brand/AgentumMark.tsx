type AgentumMarkProps = {
  className?: string;
  variant?: "full" | "mono";
};

export function AgentumMark({ className }: AgentumMarkProps) {
  return <img src="/brand/agentum-ai-app-icon.png" alt="" aria-hidden="true" className={["block", className].filter(Boolean).join(" ")} />;
}
