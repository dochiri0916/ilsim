package com.example.presentation.user.response;

import com.example.application.user.port.in.dto.UserResult;

public record UserResponse(
        String userPublicId,
        String userName,
        String departmentId,
        String departmentName
) {
    public static UserResponse from(final UserResult result) {
        return new UserResponse(
                result.userPublicId(),
                result.userName(),
                result.departmentId(),
                result.departmentName()
        );
    }
}
