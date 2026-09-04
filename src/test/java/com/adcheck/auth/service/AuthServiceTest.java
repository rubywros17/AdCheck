package com.adcheck.auth.service;

import com.adcheck.auth.dto.LoginRequest;
import com.adcheck.auth.dto.SignupRequest;
import com.adcheck.auth.dto.SignupResponse;
import com.adcheck.auth.dto.TokenResponse;
import com.adcheck.global.exception.DuplicateEmailException;
import com.adcheck.global.exception.InvalidCredentialsException;
import com.adcheck.global.security.JwtTokenProvider;
import com.adcheck.user.domain.Role;
import com.adcheck.user.domain.User;
import com.adcheck.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void signupCreatesLocalUserWithEncodedPassword() {
        SignupRequest request = new SignupRequest(" Test@Example.com ", "password123!", " 테스트 ");
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123!")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SignupResponse response = authService.signup(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User savedUser = captor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getRole()).isEqualTo(Role.USER);
        assertThat(savedUser.getProviderId()).isNull();
        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.name()).isEqualTo("테스트");
    }

    @Test
    void signupRejectsDuplicateEmail() {
        SignupRequest request = new SignupRequest("test@example.com", "password123!", "테스트");
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(DuplicateEmailException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginReturnsAccessTokenWhenPasswordMatches() {
        User user = User.createLocal("test@example.com", "encoded-password", "테스트");
        LoginRequest request = new LoginRequest("test@example.com", "password123!");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123!", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(null, "test@example.com", Role.USER))
                .thenReturn("access-token");

        TokenResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void loginRejectsMismatchedPassword() {
        User user = User.createLocal("test@example.com", "encoded-password", "테스트");
        LoginRequest request = new LoginRequest("test@example.com", "wrong-password");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtTokenProvider, never()).generateAccessToken(any(), any(), any());
    }
}
