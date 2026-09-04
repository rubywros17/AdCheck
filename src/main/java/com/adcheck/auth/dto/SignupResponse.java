package com.adcheck.auth.dto;

import com.adcheck.user.domain.Role;
import com.adcheck.user.domain.User;

public record SignupResponse(Long id, String email, String name, Role role) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}
