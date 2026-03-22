package com.example.infrastructure.user;

import com.example.application.user.port.out.UserRepository;
import com.example.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserJpaAdapter implements UserRepository {

    private final UserJpaRepository userJpaRepository;
    private final UserEntityMapper userEntityMapper;

    @Override
    public User save(final User user) {
        userJpaRepository.upsertByPublicId(user.getPublicId(), user.getName(), user.getDepartmentId());
        final UserEntity entity = userJpaRepository.findByPublicId(user.getPublicId())
                .orElseThrow(() -> new IllegalStateException("사용자 업서트에 실패했습니다. publicId=" + user.getPublicId()));
        return userEntityMapper.toDomain(entity);
    }

    @Override
    public Optional<User> findByPublicId(final String publicId) {
        return userJpaRepository.findByPublicId(publicId)
                .map(userEntityMapper::toDomain);
    }

}
