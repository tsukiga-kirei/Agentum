import { useEffect } from "react";
import { RouterProvider } from "react-router-dom";
import { useAuthStore } from "./stores/authStore";
import { appRouter } from "./routes/router";

export function App() {
  const initialized = useAuthStore((state) => state.initialized);
  const restoreSession = useAuthStore((state) => state.restoreSession);

  useEffect(() => {
    restoreSession();
  }, [restoreSession]);

  if (!initialized) {
    return null;
  }

  return <RouterProvider router={appRouter} />;
}
