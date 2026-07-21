import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import type { RefObject } from "react";
import { prefersReducedMotion } from "./prefersReducedMotion";

type UseLoginEnterOptions = {
  /** 登录 / 初始化页根容器，用于限定查询范围 */
  scopeRef: RefObject<HTMLElement | null>;
};

/**
 * 登录品牌动效：Logo 3D 翻转 + 标题字母翻转弹出 + 特性行侧翻入场 +「欢迎回来」字翻转。
 * 表单控件本身不做延迟，避免拖慢原有交互节奏。
 */
export function useLoginEnter({ scopeRef }: UseLoginEnterOptions) {
  useGSAP(
    () => {
      const root = scopeRef.current;
      if (!root) {
        return;
      }

      const mark = root.querySelector<HTMLElement>("[data-motion='brand-mark']");
      const chars = Array.from(root.querySelectorAll<HTMLElement>("[data-motion='brand-char']"));
      const subtitle = root.querySelector<HTMLElement>("[data-motion='brand-subtitle']");
      const features = Array.from(root.querySelectorAll<HTMLElement>("[data-motion='feature-item']"));
      const panelChars = Array.from(root.querySelectorAll<HTMLElement>("[data-motion='panel-char']"));

      const targets = [mark, ...chars, subtitle, ...features, ...panelChars].filter(
        (node): node is HTMLElement => Boolean(node),
      );

      if (targets.length === 0) {
        return;
      }

      if (prefersReducedMotion()) {
        gsap.set(targets, { clearProps: "all", opacity: 1, x: 0, y: 0, rotateX: 0, rotateY: 0 });
        return;
      }

      const timeline = gsap.timeline({ defaults: { ease: "power2.out" } });

      if (mark) {
        gsap.set(mark, {
          opacity: 0,
          rotateY: -72,
          transformPerspective: 800,
          transformOrigin: "50% 50%",
        });
        timeline.to(mark, { opacity: 1, rotateY: 0, duration: 0.62 }, 0);
      }

      if (chars.length > 0) {
        gsap.set(chars, {
          opacity: 0,
          rotateX: 62,
          y: 14,
          transformPerspective: 600,
          transformOrigin: "50% 100%",
        });
        timeline.to(
          chars,
          { opacity: 1, rotateX: 0, y: 0, duration: 0.5, stagger: 0.05 },
          0.14,
        );
      }

      if (subtitle) {
        gsap.set(subtitle, { opacity: 0, y: 10 });
        timeline.to(subtitle, { opacity: 1, y: 0, duration: 0.45 }, 0.48);
      }

      if (features.length > 0) {
        gsap.set(features, {
          opacity: 0,
          rotateY: 55,
          x: -18,
          transformPerspective: 700,
          transformOrigin: "0% 50%",
        });
        timeline.to(
          features,
          { opacity: 1, rotateY: 0, x: 0, duration: 0.48, stagger: 0.09 },
          0.55,
        );
      }

      if (panelChars.length > 0) {
        gsap.set(panelChars, {
          opacity: 0,
          rotateX: -55,
          y: -6,
          transformPerspective: 500,
          transformOrigin: "50% 0%",
        });
        timeline.to(
          panelChars,
          { opacity: 1, rotateX: 0, y: 0, duration: 0.45, stagger: 0.055 },
          0.22,
        );
      }
    },
    { scope: scopeRef },
  );
}
