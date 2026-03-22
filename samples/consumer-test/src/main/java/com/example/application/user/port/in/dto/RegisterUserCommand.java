package com.example.application.user.port.in.dto;

import static java.util.Objects.requireNonNull;

public record RegisterUserCommand(
        String name,
        String departmentId
) {
    public RegisterUserCommand {
        requireNonNull(name);
        requireNonNull(departmentId);
    }
}
