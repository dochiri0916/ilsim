package com.example.presentation.department.request;

import com.example.application.department.port.in.dto.RegisterDepartmentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDepartmentRequest(
        @NotBlank(message = "부서명은 필수입니다.")
        @Size(max = 100, message = "부서명은 100자 이하여야 합니다.")
        String name
) {
    public RegisterDepartmentCommand toCommand() {
        return new RegisterDepartmentCommand(name);
    }
}
