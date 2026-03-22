package com.example.application.department.port.in.dto;

import static java.util.Objects.requireNonNull;

public record RegisterDepartmentCommand(
        String name
) {
    public RegisterDepartmentCommand {
        requireNonNull(name);
    }
}
