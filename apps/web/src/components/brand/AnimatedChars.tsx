type AnimatedCharsProps = {
  text: string;
  className?: string;
  charClassName?: string;
  /** 外层语义标签，默认 span；页头标题用 h1 */
  as?: "h1" | "h2" | "span";
};

/**
 * 将文案拆成单字，供 GSAP 做翻转 / 弹出类字动画。
 * 外层保留完整 aria-label，单字对读屏隐藏。
 */
export function AnimatedChars({
  text,
  className,
  charClassName = "agent-motion-char",
  as: Tag = "span",
}: AnimatedCharsProps) {
  return (
    <Tag className={className} aria-label={text} data-motion="chrome-title">
      {Array.from(text).map((char, index) => (
        <span
          key={`${char}-${index}`}
          className={charClassName}
          data-motion="chrome-char"
          aria-hidden="true"
        >
          {char === " " ? "\u00A0" : char}
        </span>
      ))}
    </Tag>
  );
}
