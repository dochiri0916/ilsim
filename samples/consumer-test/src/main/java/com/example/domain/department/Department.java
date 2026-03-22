package com.example.domain.department;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Department {

    private String publicId;
    private String name;

    public static Department createNew(final String name) {
        return reconstitute(UUID.randomUUID().toString(), name);
    }

    public static Department reconstitute(final String publicId, final String name) {
        final Department department = new Department();
        department.publicId = requireNonNull(publicId);
        department.name = validateName(name);
        return department;
    }

    private static String validateName(final String name) {
        final String candidate = requireNonNull(name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("부서명은 공백일 수 없습니다.");
        }
        return candidate;
    }

}