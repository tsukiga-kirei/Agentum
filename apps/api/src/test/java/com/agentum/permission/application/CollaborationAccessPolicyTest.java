package com.agentum.permission.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollaborationAccessPolicyTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID READER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EDITOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Test
    void shouldAlwaysGrantOwnerEditAccess() {
        CollaborationAccessPolicy policy = new CollaborationAccessPolicy();

        CollaborationAccessPolicy.AccessLevel level = policy.resolve(
            OWNER_ID,
            OWNER_ID,
            "self",
            Set.of(),
            "self",
            Set.of()
        );

        assertThat(level).isEqualTo(CollaborationAccessPolicy.AccessLevel.OWNER);
        assertThat(level.canRead()).isTrue();
        assertThat(level.canEdit()).isTrue();
    }

    @Test
    void shouldTreatSpecifiedEditorAsReadableEvenWithoutReadGrant() {
        CollaborationAccessPolicy policy = new CollaborationAccessPolicy();

        CollaborationAccessPolicy.AccessLevel level = policy.resolve(
            OWNER_ID,
            EDITOR_ID,
            "self",
            Set.of(),
            "specified",
            Set.of(EDITOR_ID)
        );

        assertThat(level).isEqualTo(CollaborationAccessPolicy.AccessLevel.EDIT);
        assertThat(level.canRead()).isTrue();
        assertThat(level.canEdit()).isTrue();
    }

    @Test
    void shouldResolveReadAndEditScopesIndependently() {
        CollaborationAccessPolicy policy = new CollaborationAccessPolicy();

        assertThat(policy.resolve(OWNER_ID, READER_ID, "specified", Set.of(READER_ID), "self", Set.of()))
            .isEqualTo(CollaborationAccessPolicy.AccessLevel.READ);
        assertThat(policy.resolve(OWNER_ID, OTHER_ID, "all", Set.of(), "self", Set.of()))
            .isEqualTo(CollaborationAccessPolicy.AccessLevel.READ);
        assertThat(policy.resolve(OWNER_ID, OTHER_ID, "self", Set.of(), "all", Set.of()))
            .isEqualTo(CollaborationAccessPolicy.AccessLevel.EDIT);
        assertThat(policy.resolve(OWNER_ID, OTHER_ID, "self", Set.of(), "self", Set.of()))
            .isEqualTo(CollaborationAccessPolicy.AccessLevel.NONE);
    }
}
