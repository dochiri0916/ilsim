package com.example.domain.user;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class User {

    private String publicId;
    private String name;
    private String departmentId;

    public static User createNew(final String name, final String departmentId) {
        return reconstitute(UUID.randomUUID().toString(), name, departmentId);
    }

    public static User reconstitute(final String publicId, final String name, final String departmentId) {
        final User user = new User();
        user.publicId = requireNonNull(publicId);
        user.name = validateName(name);
        user.departmentId = validateDepartmentId(departmentId);
        return user;
    }

    private static String validateName(final String name) {
        final String candidate = requireNonNull(name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("사용자 이름은 공백일 수 없습니다.");
        }
        return candidate;
    }

    private static String validateDepartmentId(final String departmentId) {
        final String candidate = requireNonNull(departmentId);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("부서 식별자는 공백일 수 없습니다.");
        }
        return candidate;
    }

}
