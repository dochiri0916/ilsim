package com.example.application.department.port.in.dto;

import com.example.domain.department.Department;

public record RegisterDepartmentResult(
        String publicId,
        String name
) {
    public static RegisterDepartmentResult from(final Department department) {
        return new RegisterDepartmentResult(
                department.getPublicId(),
                department.getName()
        );
    }
}
