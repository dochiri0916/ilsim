package com.example.infrastructure.user;

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
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    @Column(nullable = false, unique = true, updatable = false)
    private String publicId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String departmentId;

    public static UserEntity fromValues(
            final String publicId,
            final String name,
            final String departmentId
    ) {
        final UserEntity entity = new UserEntity();
        entity.publicId = requireNonNull(publicId);
        entity.name = requireNonNull(name);
        entity.departmentId = requireNonNull(departmentId);
        return entity;
    }

}
