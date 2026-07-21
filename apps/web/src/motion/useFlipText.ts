import { useEffect, type RefObject } from "react";
import gsap from "gsap";
import { prefersReducedMotion } from "./prefersReducedMotion";

/**
 * 文案容器轻量翻转入场（模块 Segmented 描述等）。
 * 幅度与时长适中，避免过快晃眼。
 */
export function useFlipText(ref: RefObject<HTMLElement | null>, replayKey: string | number) {
  useEffect(() => {
    const el = ref.current;
    if (!el || prefersReducedMotion()) {
      return;
    }

    gsap.fromTo(
      el,
      { rotateX: -48, opacity: 0, transformPerspective: 480, transformOrigin: "50% 0%" },
      { rotateX: 0, opacity: 1, duration: 0.42, ease: "power2.out" },
    );
  }, [ref, replayKey]);
}
