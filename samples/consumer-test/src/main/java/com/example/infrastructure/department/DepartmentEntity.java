package com.example.infrastructure.department;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static java.util.Objects.requireNonNull;

@Entity
@Table(name = "departments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepartmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    @Column(nullable = false, unique = true, updatable = false)
    private String publicId;

    @Column(nullable = false)
    private String name;

    public static DepartmentEntity fromValues(final String publicId, final String name) {
        final DepartmentEntity entity = new DepartmentEntity();
        entity.publicId = requireNonNull(publicId);
        entity.name = requireNonNull(name);
        return entity;
    }

}
