package com.example.application.user.port.in.dto;

import com.example.domain.user.User;

public record RegisterUserResult(
        String publicId,
        String name,
        String departmentId
) {
    public static RegisterUserResult from(final User user) {
        return new RegisterUserResult(
                user.getPublicId(),
                user.getName(),
                user.getDepartmentId()
        );
    }
}
