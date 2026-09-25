package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.merchant.Role;
import org.junit.jupiter.api.Test;

class RoleTest {
    @Test
    void viewersCannotWriteAndDevelopersCannotManageMemberships() {
        assertThat(Role.VIEWER.canWrite()).isFalse();
        assertThat(Role.DEVELOPER.canWrite()).isTrue();
        assertThat(Role.DEVELOPER.canManage()).isFalse();
        assertThat(Role.ADMIN.canManage()).isTrue();
        assertThat(Role.OWNER.canManage()).isTrue();
    }
}
