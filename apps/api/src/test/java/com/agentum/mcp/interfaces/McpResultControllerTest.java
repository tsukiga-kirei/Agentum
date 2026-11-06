package com.agentum.mcp.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.mcp.application.McpDatasetService;
import com.agentum.workbench.application.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpResultControllerTest {
    @Test void checksRunAccessBeforeReadingFullResult() {
        var access = mock(WorkbenchAccess.class); var runs = mock(WorkbenchRuntimeService.class); var results = mock(McpDatasetService.class);
        var controller = new McpResultController(access, runs, results);
        UUID tenant = UUID.randomUUID(), run = UUID.randomUUID(), id = UUID.randomUUID(), userId = UUID.randomUUID();
        var user = mock(CurrentUserPrincipal.class); when(user.userId()).thenReturn(userId);
        when(results.download(tenant, run, userId, id.toString())).thenReturn("[]".getBytes());
        var response = controller.download(tenant, run, id, user);
        var order = inOrder(access, runs, results);
        order.verify(access).assertCanAccessWorkbenchOrSchedule(user, tenant);
        order.verify(runs).getRunDetail(tenant, user, run);
        order.verify(results).download(tenant, run, userId, id.toString());
        assertThat(response.getBody().getByteArray()).isEqualTo("[]".getBytes());
        reset(results); when(runs.getRunDetail(tenant, user, run)).thenThrow(new IllegalArgumentException("无权查看流程"));
        assertThatThrownBy(() -> controller.download(tenant, run, id, user)).hasMessageContaining("无权");
        verifyNoInteractions(results);
    }
}
