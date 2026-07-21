import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import type { RefObject } from "react";
import { prefersReducedMotion } from "./prefersReducedMotion";

type UseChromeEnterOptions = {
  scopeRef: RefObject<HTMLElement | null>;
  /** 标题文案变化时重播字动画（如运行态任务名） */
  deps?: unknown[];
};

/**
 * 工作台页头动效：图标 3D 翻转 + 标题逐字翻转。
 * 节奏偏适中，ease 用 power2 减少弹性造成的晃眼。
 */
export function useChromeEnter({ scopeRef, deps = [] }: UseChromeEnterOptions) {
  useGSAP(
    () => {
      const root = scopeRef.current;
      if (!root) {
        return;
      }

      const mark = root.querySelector<HTMLElement>("[data-motion='chrome-mark']");
      const chars = Array.from(root.querySelectorAll<HTMLElement>("[data-motion='chrome-char']"));
      const description = root.querySelector<HTMLElement>("[data-motion='chrome-description']");
      const badge = root.querySelector<HTMLElement>("[data-motion='chrome-badge']");

      const targets = [mark, ...chars, description, badge].filter(
        (node): node is HTMLElement => Boolean(node),
      );

      if (targets.length === 0) {
        return;
      }

      if (prefersReducedMotion()) {
        gsap.set(targets, { clearProps: "all", opacity: 1, x: 0, y: 0, rotateX: 0, rotateY: 0, scale: 1 });
        return;
      }

      const timeline = gsap.timeline({ defaults: { ease: "power2.out" } });

      if (mark) {
        gsap.set(mark, {
          opacity: 0,
          rotateY: -72,
          scale: 0.92,
          transformPerspective: 700,
          transformOrigin: "50% 50%",
        });
        timeline.to(mark, { opacity: 1, rotateY: 0, scale: 1, duration: 0.55 }, 0);
      }

      if (chars.length > 0) {
        gsap.set(chars, {
          opacity: 0,
          rotateX: 58,
          y: 10,
          transformPerspective: 520,
          transformOrigin: "50% 100%",
        });
        timeline.to(
          chars,
          { opacity: 1, rotateX: 0, y: 0, duration: 0.48, stagger: 0.038 },
          0.12,
        );
      }

      if (badge) {
        gsap.set(badge, { opacity: 0, scale: 0.92 });
        timeline.to(badge, { opacity: 1, scale: 1, duration: 0.4 }, 0.28);
      }

      if (description) {
        gsap.set(description, { opacity: 0, y: 8 });
        timeline.to(description, { opacity: 1, y: 0, duration: 0.42 }, 0.3);
      }
    },
    { scope: scopeRef, dependencies: deps },
  );
}
