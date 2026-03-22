package com.example.infrastructure.department;

import com.example.application.department.port.out.DepartmentRepository;
import com.example.domain.department.Department;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DepartmentJpaAdapter implements DepartmentRepository {

    private final DepartmentJpaRepository jpaRepo;
    private final DepartmentEntityMapper mapper;

    @Override
    public Department save(final Department department) {
        jpaRepo.upsertByPublicId(department.getPublicId(), department.getName());
        final DepartmentEntity entity = jpaRepo.findByPublicId(department.getPublicId())
                .orElseThrow(() -> new IllegalStateException("부서 업서트에 실패했습니다. publicId=" + department.getPublicId()));
        return mapper.toDomain(entity);
    }

    @Override
    public Optional<Department> findByPublicId(final String publicId) {
        return jpaRepo.findByPublicId(publicId)
                .map(mapper::toDomain);
    }

}
