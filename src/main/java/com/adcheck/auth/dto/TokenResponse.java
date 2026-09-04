package com.adcheck.auth.dto;

public record TokenResponse(String accessToken, String tokenType) {

    public static TokenResponse bearer(String accessToken) {
        return new TokenResponse(accessToken, "Bearer");
    }
}
