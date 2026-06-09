import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "../stores/authStore";
import { NoAccessPage } from "./NoAccessPage";
import { firstAllowedSurfacePath, surfaceFromPath } from "./paths";

export function MenuGuard() {
  const menus = useAuthStore((state) => state.menus);
  const location = useLocation();

  if (menus.length === 0) {
    return <NoAccessPage />;
  }

  const surface = surfaceFromPath(location.pathname);
  if (surface && !menus.some((menu) => menu.key === surface)) {
    return <Navigate to={firstAllowedSurfacePath(menus)} replace />;
  }

  return <Outlet />;
}
