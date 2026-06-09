import { Navigate } from "react-router-dom";
import { useAuthStore } from "../stores/authStore";
import { firstAllowedSurfacePath } from "./paths";

export function DefaultRedirect() {
  const menus = useAuthStore((state) => state.menus);
  return <Navigate to={firstAllowedSurfacePath(menus)} replace />;
}
