package com.example.application.department.service;

import com.example.application.department.port.in.RegisterDepartmentUseCase;
import com.example.application.department.port.in.dto.RegisterDepartmentCommand;
import com.example.application.department.port.in.dto.RegisterDepartmentResult;
import com.example.application.department.port.out.DepartmentRepository;
import com.example.domain.department.Department;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class RegisterDepartmentService implements RegisterDepartmentUseCase {

    private final DepartmentRepository departmentRepo;

    @Override
    @Transactional
    public RegisterDepartmentResult register(final RegisterDepartmentCommand command) {
        final Department department = Department.createNew(command.name());
        return RegisterDepartmentResult.from(departmentRepo.save(department));
    }

}
