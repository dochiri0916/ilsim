package com.example.infrastructure.department;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DepartmentJpaRepository extends JpaRepository<DepartmentEntity, Long> {

    Optional<DepartmentEntity> findByPublicId(String publicId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            MERGE INTO departments (public_id, name)
            KEY(public_id)
            VALUES (:publicId, :name)
            """, nativeQuery = true)
    void upsertByPublicId(
            @Param("publicId") String publicId,
            @Param("name") String name
    );

}
