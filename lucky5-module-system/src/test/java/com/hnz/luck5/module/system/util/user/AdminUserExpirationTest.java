package com.hnz.luck5.module.system.util.user;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AdminUserExpirationTest {

    @Test
    void expiresAtTheExactBoundary() {
        LocalDateTime deadline = LocalDateTime.of(2026, 9, 28, 18, 0, 0);
        assertFalse(AdminUserExpiration.isExpired(deadline, deadline.minusNanos(1)));
        assertTrue(AdminUserExpiration.isExpired(deadline, deadline));
        assertTrue(AdminUserExpiration.isExpired(deadline, deadline.plusNanos(1)));
    }

    @Test
    void toleratesLegacyNullOnly() {
        assertFalse(AdminUserExpiration.isExpired(null, LocalDateTime.now()));
    }
}
