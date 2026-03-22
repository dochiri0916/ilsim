package com.example.application.department.port.in;

import com.example.application.department.port.in.dto.RegisterDepartmentCommand;
import com.example.application.department.port.in.dto.RegisterDepartmentResult;

@FunctionalInterface
public interface RegisterDepartmentUseCase {
    RegisterDepartmentResult register(RegisterDepartmentCommand command);
}
