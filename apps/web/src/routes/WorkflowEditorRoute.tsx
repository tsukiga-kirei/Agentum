import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { App } from "antd";
import { WorkflowEditorPage } from "../surfaces/designer/WorkflowEditorPage";
import type { WorkflowDraft } from "../surfaces/designer/WorkflowDraftsPage";
import { AgentumApiError, workflowApi } from "../services/apiClient";
import { useAuthStore } from "../stores/authStore";
import { paths } from "./paths";

export function WorkflowEditorRoute() {
  const { workflowId } = useParams<{ workflowId: string }>();
  const navigate = useNavigate();
  const token = useAuthStore((state) => state.token);
  const tenantId = useAuthStore((state) => state.user?.tenantId ?? "");
  const [workflow, setWorkflow] = useState<WorkflowDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const { message: messageApi } = App.useApp();

  useEffect(() => {
    if (!workflowId || !token || !tenantId) {
      setWorkflow(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);

    void workflowApi.getDraft(tenantId, workflowId, token)
      .then((detail) => {
        if (!cancelled) {
          setWorkflow(detail.draft);
        }
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        const reason = error instanceof AgentumApiError ? error.message : "流程草稿加载失败";
        messageApi.error(reason);
        navigate(paths.designer.mine, { replace: true });
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [messageApi, navigate, tenantId, token, workflowId]);

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-[var(--color-primary)]" aria-hidden="true" />
      </div>
    );
  }

  if (!workflow) {
    return null;
  }

  return (
    <>
      <WorkflowEditorPage
        workflow={workflow}
        onBack={() => navigate(paths.designer.mine)}
        onDraftSaved={(draft) => setWorkflow(draft)}
      />
    </>
  );
}
