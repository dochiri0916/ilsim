package com.example.domain.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTest {
    private static final String NAME = "song";
    private static final String DEPARTMENT_ID = "dept-public-id";
    private static final String PUBLIC_ID = "14f1f1a0-4ab5-4f1a-bf50-c9e0e8162e95";

    @Test
    void createNewCreatesUserWithGeneratedPublicId() {
        final User user = User.createNew(NAME, DEPARTMENT_ID);

        assertNotNull(user.getPublicId(), "Public ID should be generated when creating new user");
    }

    @Test
    void createNewCreatesUserWithDepartmentId() {
        final User user = User.createNew(NAME, DEPARTMENT_ID);

        assertEquals(DEPARTMENT_ID, user.getDepartmentId(), "Department ID should be preserved");
    }

    @Test
    void reconstitutePreservesPublicId() {
        final User user = User.reconstitute(PUBLIC_ID, NAME, DEPARTMENT_ID);

        assertEquals(PUBLIC_ID, user.getPublicId(), "Public ID should match input value");
    }

    @Test
    void createNewRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> User.createNew(" ", DEPARTMENT_ID));
    }

    @Test
    void createNewRejectsBlankDepartmentId() {
        assertThrows(IllegalArgumentException.class, () -> User.createNew(NAME, " "));
    }
}
