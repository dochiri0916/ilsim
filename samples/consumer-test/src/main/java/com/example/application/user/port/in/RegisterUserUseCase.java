package com.example.application.user.port.in;

import com.example.application.user.port.in.dto.RegisterUserCommand;
import com.example.application.user.port.in.dto.RegisterUserResult;

@FunctionalInterface
public interface RegisterUserUseCase {
    RegisterUserResult register(RegisterUserCommand command);
}
