package com.example.infrastructure.user;

import com.example.application.user.port.out.UserReadPort;
import com.example.application.user.port.out.dto.UserReadModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserQueryDslAdapter implements UserReadPort {

    private final UserQueryDslRepository userQueryRepo;

    @Override
    public Optional<UserReadModel> findByPublicId(final String publicId) {
        return userQueryRepo.findByPublicId(publicId);
    }

}
