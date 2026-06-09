import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppLayout } from "../layouts/AppLayout";
import { AssetsPage } from "../surfaces/assets/AssetsPage";
import { LoginPage } from "../surfaces/auth/LoginPage";
import { WorkflowDraftsPage } from "../surfaces/designer/WorkflowDraftsPage";
import { TenantManagementPage } from "../surfaces/admin/TenantManagementPage";
import { SystemManagementPage } from "../surfaces/admin/SystemManagementPage";
import { WorkbenchShell } from "../surfaces/workbench/WorkbenchShell";
import { DefaultRedirect } from "./DefaultRedirect";
import { MenuGuard } from "./MenuGuard";
import { NotFoundPage } from "./NotFoundPage";
import { ProtectedRoute } from "./ProtectedRoute";
import { WorkflowEditorRoute } from "./WorkflowEditorRoute";
import { paths } from "./paths";

export const appRouter = createBrowserRouter([
  {
    path: paths.login,
    element: <LoginPage />,
  },
  {
    path: "/",
    element: <Navigate to={paths.app} replace />,
  },
  {
    path: paths.app,
    element: <ProtectedRoute />,
    children: [
      {
        element: <MenuGuard />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { index: true, element: <DefaultRedirect /> },

              {
                path: "workbench",
                children: [
                  { index: true, element: <WorkbenchShell /> },
                  { path: "create", element: <WorkbenchShell /> },
                  { path: "tasks", element: <WorkbenchShell /> },
                  { path: "runs/:runId", element: <WorkbenchShell /> },
                ],
              },

              {
                path: "designer",
                children: [
                  { index: true, element: <WorkflowDraftsPage /> },
                  { path: "shared", element: <WorkflowDraftsPage /> },
                  { path: "mine", element: <WorkflowDraftsPage /> },
                  { path: "workflows/:workflowId", element: <WorkflowEditorRoute /> },
                ],
              },

              {
                path: "assets",
                children: [
                  { index: true, element: <AssetsPage /> },
                  { path: "open", element: <AssetsPage /> },
                  { path: "mine", element: <AssetsPage /> },
                ],
              },

              {
                path: "tenant",
                children: [
                  { index: true, element: <Navigate to={paths.tenant.organization} replace /> },
                  { path: "organization", element: <TenantManagementPage /> },
                  { path: "roles", element: <TenantManagementPage /> },
                  { path: "resources", element: <TenantManagementPage /> },
                ],
              },

              {
                path: "system",
                children: [
                  { index: true, element: <Navigate to={paths.system.overview} replace /> },
                  { path: "overview", element: <SystemManagementPage /> },
                  { path: "tenants", element: <SystemManagementPage /> },
                  { path: "models", element: <SystemManagementPage /> },
                  { path: "capabilities", element: <SystemManagementPage /> },
                ],
              },

              { path: "*", element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to={paths.app} replace /> },
]);
