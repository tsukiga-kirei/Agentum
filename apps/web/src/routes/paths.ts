export type SurfaceKey = "workbench" | "designer" | "assets" | "tenant" | "system" | "audit";

export const paths = {
  login: "/login",
  setup: "/setup",
  app: "/app",
  workbench: {
    root: "/app/workbench",
    create: "/app/workbench/create",
    tasks: "/app/workbench/tasks",
    schedules: "/app/workbench/schedules",
    run: (runId: string) => `/app/workbench/runs/${runId}`,
  },
  designer: {
    root: "/app/designer",
    shared: "/app/designer/shared",
    mine: "/app/designer/mine",
    workflow: (workflowId: string) => `/app/designer/workflows/${workflowId}`,
  },
  assets: {
    root: "/app/assets",
    open: "/app/assets/open",
    mine: "/app/assets/mine",
  },
  tenant: {
    root: "/app/tenant",
    organization: "/app/tenant/organization",
    roles: "/app/tenant/roles",
    resources: "/app/tenant/resources",
  },
  system: {
    root: "/app/system",
    overview: "/app/system/overview",
    tenants: "/app/system/tenants",
    models: "/app/system/models",
    capabilities: "/app/system/capabilities",
    settings: "/app/system/settings",
  },
  audit: {
    root: "/app/audit",
    runs: "/app/audit/runs",
    tools: "/app/audit/tools",
    operations: "/app/audit/operations",
  },
} as const;

export function surfaceFromPath(pathname: string): SurfaceKey | null {
  const segment = pathname.split("/")[2];
  if (
    segment === "workbench"
    || segment === "designer"
    || segment === "assets"
    || segment === "tenant"
    || segment === "system"
    || segment === "audit"
  ) {
    return segment;
  }
  return null;
}

export function defaultPathForSurface(surface: SurfaceKey): string {
  switch (surface) {
    case "workbench":
      return paths.workbench.root;
    case "designer":
      return paths.designer.root;
    case "assets":
      return paths.assets.root;
    case "tenant":
      return paths.tenant.organization;
    case "system":
      return paths.system.overview;
    case "audit":
      return paths.audit.runs;
  }
}

export function firstAllowedSurfacePath(menus: Array<{ key: string }>): string {
  const first = menus[0]?.key as SurfaceKey | undefined;
  if (!first) {
    return paths.app;
  }
  return defaultPathForSurface(first);
}

export function surfaceNavPath(surface: SurfaceKey): string {
  return defaultPathForSurface(surface);
}

export function parsePositiveInt(value: string | null, fallback = 1): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return fallback;
  }
  return Math.floor(parsed);
}
