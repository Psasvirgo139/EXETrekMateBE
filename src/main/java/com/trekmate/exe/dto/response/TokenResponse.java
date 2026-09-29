package com.trekmate.exe.dto.response;

public record TokenResponse(
        String token,
        long expiresInSeconds
) {}
