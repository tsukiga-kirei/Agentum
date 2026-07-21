type AnimatedBrandTitleProps = {
  text: string;
  className?: string;
};

/**
 * 将品牌标题拆成单字 span，供 GSAP 做字母翻转入场。
 * 外层保留完整文案作无障碍标签。
 */
export function AnimatedBrandTitle({ text, className }: AnimatedBrandTitleProps) {
  return (
    <h1 className={className} aria-label={text} data-motion="brand-title">
      {Array.from(text).map((char, index) => (
        <span
          key={`${char}-${index}`}
          className="login-brand-char"
          data-motion="brand-char"
          aria-hidden="true"
        >
          {char === " " ? "\u00A0" : char}
        </span>
      ))}
    </h1>
  );
}
