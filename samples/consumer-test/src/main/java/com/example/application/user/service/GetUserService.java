package com.example.application.user.service;

import com.example.application.user.port.in.GetUserUseCase;
import com.example.application.user.port.in.dto.UserResult;
import com.example.application.user.port.out.UserReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class GetUserService implements GetUserUseCase {

    private final UserReadPort userReadPort;

    @Override
    @Transactional(readOnly = true)
    public UserResult getByPublicId(final String publicId) {
        return UserResult.from(userReadPort.loadByPublicId(publicId));
    }

}
