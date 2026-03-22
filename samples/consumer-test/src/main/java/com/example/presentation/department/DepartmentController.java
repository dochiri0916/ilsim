package com.example.presentation.department;

import com.example.application.department.port.in.RegisterDepartmentUseCase;
import com.example.presentation.department.request.RegisterDepartmentRequest;
import com.example.presentation.department.response.RegisterDepartmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final RegisterDepartmentUseCase regDeptUc;

    @PostMapping
    public ResponseEntity<RegisterDepartmentResponse> createDepartment(
            @Valid @RequestBody final RegisterDepartmentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RegisterDepartmentResponse.from(regDeptUc.register(request.toCommand())));
    }

}
