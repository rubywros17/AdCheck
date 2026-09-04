package com.adcheck.user.dto;

import com.adcheck.user.domain.Role;
import com.adcheck.user.domain.User;

public record UserResponse(Long id, String email, String name, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
