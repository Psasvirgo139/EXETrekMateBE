package com.trekmate.exe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TokenRequest(
        @NotBlank @Size(min = 16, max = 16) String userId,
        @NotBlank String nonce,
        @NotBlank String signature
) {}
