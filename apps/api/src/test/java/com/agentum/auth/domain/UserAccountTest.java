package com.agentum.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserAccountTest {

    @Test
    void shouldCreateActiveUserAccount() {
        UserAccount user = UserAccount.create("new.user", "{bcrypt}hash", "新成员", "new.user@example.com");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getUsername()).isEqualTo("new.user");
        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(user.getDisplayName()).isEqualTo("新成员");
        assertThat(user.getEmail()).isEqualTo("new.user@example.com");
        assertThat(user.getAvatarUrl()).isEmpty();
        assertThat(user.getStatus()).isEqualTo("active");
    }
}
