package com.example.presentation.user.response;

import com.example.application.user.port.in.dto.RegisterUserResult;

public record RegisterUserResponse(
        String publicId,
        String name,
        String departmentId
) {
    public static RegisterUserResponse from(final RegisterUserResult result) {
        return new RegisterUserResponse(
                result.publicId(),
                result.name(),
                result.departmentId()
        );
    }
}
