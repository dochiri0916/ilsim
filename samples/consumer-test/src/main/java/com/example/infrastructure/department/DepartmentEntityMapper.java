package com.example.infrastructure.department;

import com.example.domain.department.Department;
import org.springframework.stereotype.Component;

@Component
public class DepartmentEntityMapper {

    public Department toDomain(final DepartmentEntity entity) {
        return Department.reconstitute(entity.getPublicId(), entity.getName());
    }

}
