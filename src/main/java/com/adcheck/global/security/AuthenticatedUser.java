package com.adcheck.global.security;

import com.adcheck.user.domain.Role;

public record AuthenticatedUser(Long id, String email, Role role) {
}
