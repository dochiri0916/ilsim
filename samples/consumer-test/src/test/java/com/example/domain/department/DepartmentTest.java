package com.example.domain.department;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DepartmentTest {
    private static final String NAME = "Platform";
    private static final String PUBLIC_ID = "14f1f1a0-4ab5-4f1a-bf50-c9e0e8162e95";

    @Test
    void createNewCreatesDepartmentWithGeneratedPublicId() {
        final Department department = Department.createNew(NAME);

        assertNotNull(department.getPublicId(), "Public ID should be generated when creating new department");
    }

    @Test
    void createNewCreatesDepartmentWithName() {
        final Department department = Department.createNew(NAME);

        assertEquals(NAME, department.getName(), "Department name should be preserved");
    }

    @Test
    void reconstitutePreservesPublicId() {
        final Department department = Department.reconstitute(PUBLIC_ID, NAME);

        assertEquals(PUBLIC_ID, department.getPublicId(), "Public ID should match input value");
    }

    @Test
    void createNewRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> Department.createNew(" "));
    }
}
