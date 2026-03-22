package com.example.application.user.port.in.dto;

import com.example.application.user.port.out.dto.UserReadModel;

public record UserResult(
        String userPublicId,
        String userName,
        String departmentId,
        String departmentName
) {
    public static UserResult from(final UserReadModel view) {
        return new UserResult(
                view.userPublicId(),
                view.userName(),
                view.departmentId(),
                view.departmentName()
        );
    }
}
