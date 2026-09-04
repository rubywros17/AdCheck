package com.adcheck.global.security;

import com.adcheck.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String encodedSecret,
            @Value("${jwt.expiration}") long expirationMillis
    ) {
        try {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodedSecret));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "JWT_SECRET must be a Base64-encoded key of at least 256 bits.", exception
            );
        }
        if (expirationMillis <= 0) {
            throw new IllegalStateException("JWT_EXPIRATION must be greater than zero.");
        }
        this.expirationMillis = expirationMillis;
    }

    public String generateAccessToken(Long userId, String email, Role role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("email", email)
                .claim("role", role.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public AuthenticatedUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            Role role = Role.valueOf(claims.get("role", String.class));
            return new AuthenticatedUser(userId, email, role);
        } catch (ExpiredJwtException exception) {
            throw new JwtTokenException("JWT_EXPIRED", "JWT가 만료되었습니다.");
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtTokenException("JWT_INVALID", "유효하지 않은 JWT입니다.");
        }
    }
}
