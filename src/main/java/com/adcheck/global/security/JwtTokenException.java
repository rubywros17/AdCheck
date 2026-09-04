package com.adcheck.global.security;

public class JwtTokenException extends RuntimeException {

    private final String code;

    public JwtTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
