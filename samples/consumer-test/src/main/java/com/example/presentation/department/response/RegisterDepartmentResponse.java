package com.example.presentation.department.response;

import com.example.application.department.port.in.dto.RegisterDepartmentResult;

public record RegisterDepartmentResponse(
        String publicId,
        String name
) {
    public static RegisterDepartmentResponse from(final RegisterDepartmentResult result) {
        return new RegisterDepartmentResponse(
                result.publicId(),
                result.name()
        );
    }
}
