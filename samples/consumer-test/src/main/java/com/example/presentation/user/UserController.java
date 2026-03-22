package com.example.presentation.user;

import com.example.application.user.port.in.GetUserUseCase;
import com.example.application.user.port.in.RegisterUserUseCase;
import com.example.presentation.user.request.RegisterUserRequest;
import com.example.presentation.user.response.RegisterUserResponse;
import com.example.presentation.user.response.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final RegisterUserUseCase regUserUc;
    private final GetUserUseCase getUserUc;

    @PostMapping
    public ResponseEntity<RegisterUserResponse> createUser(@Valid @RequestBody final RegisterUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RegisterUserResponse.from(regUserUc.register(request.toCommand())));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<UserResponse> getUser(
            @PathVariable final String publicId
    ) {
        return ResponseEntity.ok(
                UserResponse.from(getUserUc.getByPublicId(publicId))
        );
    }

}
