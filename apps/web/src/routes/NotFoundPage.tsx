import { Link } from "react-router-dom";
import { paths } from "./paths";

export function NotFoundPage() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 px-6 text-center">
      <h1 className="text-lg font-semibold text-[var(--color-text-primary)]">页面不存在</h1>
      <p className="text-sm text-[var(--color-text-secondary)]">请检查链接是否正确，或返回工作台首页。</p>
      <Link to={paths.app} className="sys-btn sys-btn--primary text-xs">
        返回首页
      </Link>
    </div>
  );
}
