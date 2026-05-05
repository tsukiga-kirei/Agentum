type AgentumMarkProps = {
  className?: string;
  variant?: "full" | "mono";
};

export function AgentumMark({ className, variant = "full" }: AgentumMarkProps) {
  const source = variant === "mono" ? "/brand/agentum-mark-monochrome.svg" : "/brand/agentum-mark.svg";

  return <img src={source} alt="" aria-hidden="true" className={["block", className].filter(Boolean).join(" ")} />;
}
