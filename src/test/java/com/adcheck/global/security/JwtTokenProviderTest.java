package com.adcheck.global.security;

import com.adcheck.user.domain.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String TEST_SECRET =
            "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test
    void generatesAndParsesAccessToken() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_SECRET, 3_600_000);

        String token = provider.generateAccessToken(1L, "test@example.com", Role.USER);
        AuthenticatedUser principal = provider.parseToken(token);

        assertThat(token).isNotBlank();
        assertThat(principal.id()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("test@example.com");
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    void rejectsInvalidToken() {
        JwtTokenProvider provider = new JwtTokenProvider(TEST_SECRET, 3_600_000);

        assertThatThrownBy(() -> provider.parseToken("not-a-jwt"))
                .isInstanceOfSatisfying(JwtTokenException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("JWT_INVALID"));
    }
}
