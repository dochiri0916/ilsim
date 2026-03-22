package com.example.infrastructure.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByPublicId(String publicId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            MERGE INTO users (public_id, name, department_id)
            KEY(public_id)
            VALUES (:publicId, :name, :departmentId)
            """, nativeQuery = true)
    void upsertByPublicId(
            @Param("publicId") String publicId,
            @Param("name") String name,
            @Param("departmentId") String departmentId
    );

}
