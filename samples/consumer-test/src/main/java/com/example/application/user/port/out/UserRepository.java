package com.example.application.user.port.out;

import java.util.Optional;
import com.example.domain.user.User;

public interface UserRepository {

    User save(User user);

    Optional<User> findByPublicId(String publicId);

    default User loadByPublicId(final String publicId) {
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다. publicId=" + publicId));
    }

}
