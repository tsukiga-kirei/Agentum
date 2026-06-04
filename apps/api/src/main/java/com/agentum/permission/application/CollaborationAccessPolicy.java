package com.agentum.permission.application;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CollaborationAccessPolicy {

    public static final String SCOPE_SELF = "self";
    public static final String SCOPE_SPECIFIED = "specified";
    public static final String SCOPE_ALL = "all";

    public AccessLevel resolve(
        UUID ownerUserId,
        UUID operatorUserId,
        String readScope,
        Set<UUID> readUserIds,
        String editScope,
        Set<UUID> editUserIds
    ) {
        if (ownerUserId != null && ownerUserId.equals(operatorUserId)) {
            return AccessLevel.OWNER;
        }
        if (matches(operatorUserId, editScope, editUserIds)) {
            // 编辑权限天然包含读取权限，避免出现可写入但无法读取资源详情的矛盾状态。
            return AccessLevel.EDIT;
        }
        if (matches(operatorUserId, readScope, readUserIds)) {
            return AccessLevel.READ;
        }
        return AccessLevel.NONE;
    }

    public boolean isSupportedScope(String scope) {
        return SCOPE_SELF.equals(scope) || SCOPE_SPECIFIED.equals(scope) || SCOPE_ALL.equals(scope);
    }

    private boolean matches(UUID operatorUserId, String scope, Set<UUID> userIds) {
        if (operatorUserId == null) {
            return false;
        }
        if (SCOPE_ALL.equals(scope)) {
            return true;
        }
        return SCOPE_SPECIFIED.equals(scope) && userIds != null && userIds.contains(operatorUserId);
    }

    public enum AccessLevel {
        NONE(false, false),
        READ(true, false),
        EDIT(true, true),
        OWNER(true, true);

        private final boolean readable;
        private final boolean editable;

        AccessLevel(boolean readable, boolean editable) {
            this.readable = readable;
            this.editable = editable;
        }

        public boolean canRead() {
            return readable;
        }

        public boolean canEdit() {
            return editable;
        }
    }
}
