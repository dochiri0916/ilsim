package com.example.infrastructure.user;

import com.example.domain.user.User;
import org.springframework.stereotype.Component;

@Component
public class UserEntityMapper {

    public User toDomain(final UserEntity entity) {
        return User.reconstitute(entity.getPublicId(), entity.getName(), entity.getDepartmentId());
    }

}
