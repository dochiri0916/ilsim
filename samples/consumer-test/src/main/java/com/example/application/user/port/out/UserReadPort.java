package com.example.application.user.port.out;

import com.example.application.user.port.out.dto.UserReadModel;

import java.util.Optional;

@FunctionalInterface
public interface UserReadPort {

    Optional<UserReadModel> findByPublicId(String publicId);

    default UserReadModel loadByPublicId(final String publicId) {
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다. publicId=" + publicId));
    }

}
