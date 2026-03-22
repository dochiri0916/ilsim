package com.example.application.department.port.out;

import com.example.domain.department.Department;

import java.util.Optional;

public interface DepartmentRepository {

    Department save(Department department);

    Optional<Department> findByPublicId(String publicId);

}
