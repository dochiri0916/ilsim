package com.example.application.user.port.in;

import com.example.application.user.port.in.dto.UserResult;

@FunctionalInterface
public interface GetUserUseCase {
    UserResult getByPublicId(String publicId);
}
