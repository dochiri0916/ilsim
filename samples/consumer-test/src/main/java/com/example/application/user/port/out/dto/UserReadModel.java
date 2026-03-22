package com.example.application.user.port.out.dto;

public record UserReadModel(
        String userPublicId,
        String userName,
        String departmentId,
        String departmentName
) {
}
