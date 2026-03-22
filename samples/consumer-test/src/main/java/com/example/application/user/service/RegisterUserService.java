package com.example.application.user.service;

import com.example.application.user.port.in.RegisterUserUseCase;
import com.example.application.user.port.in.dto.RegisterUserCommand;
import com.example.application.user.port.in.dto.RegisterUserResult;
import com.example.application.user.port.out.UserRepository;
import com.example.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public final class RegisterUserService implements RegisterUserUseCase {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public RegisterUserResult register(final RegisterUserCommand command) {
        final User user = User.createNew(command.name(), command.departmentId());
        return RegisterUserResult.from(userRepository.save(user));
    }

}