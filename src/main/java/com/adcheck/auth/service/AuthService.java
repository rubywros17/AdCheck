package com.adcheck.auth.service;

import com.adcheck.auth.dto.LoginRequest;
import com.adcheck.auth.dto.SignupRequest;
import com.adcheck.auth.dto.SignupResponse;
import com.adcheck.auth.dto.TokenResponse;
import com.adcheck.global.exception.DuplicateEmailException;
import com.adcheck.global.exception.InvalidCredentialsException;
import com.adcheck.global.security.JwtTokenProvider;
import com.adcheck.user.domain.AuthProvider;
import com.adcheck.user.domain.User;
import com.adcheck.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.createLocal(email, encodedPassword, request.name().trim());
        return SignupResponse.from(userRepository.save(user));
    }

    public TokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
                .filter(found -> found.getAuthProvider() == AuthProvider.LOCAL)
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole()
        );
        return TokenResponse.bearer(accessToken);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
