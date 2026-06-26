import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "../stores/authStore";
import { paths } from "./paths";

export function ProtectedRoute() {
  const user = useAuthStore((state) => state.user);
  const initialized = useAuthStore((state) => state.initialized);
  const bootstrapRequired = useAuthStore((state) => state.bootstrapRequired);
  const location = useLocation();

  if (!initialized) {
    return null;
  }

  if (bootstrapRequired) {
    return <Navigate to={paths.setup} replace />;
  }

  if (!user) {
    return <Navigate to={paths.login} replace state={{ from: location }} />;
  }

  return <Outlet />;
}
