package com.example.presentation.user.request;

import com.example.application.user.port.in.dto.RegisterUserCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterUserRequest(
        @NotBlank(message = "사용자 이름은 필수입니다.")
        @Size(max = 50, message = "사용자 이름은 50자 이하여야 합니다.")
        String name,
        @NotBlank(message = "부서 식별자는 필수입니다.")
        String departmentId
) {
    public RegisterUserCommand toCommand() {
        return new RegisterUserCommand(name, departmentId);
    }
}
