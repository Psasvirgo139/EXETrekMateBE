package com.trekmate.exe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank @Size(min = 16, max = 16) String userId,
        @NotBlank String publicKey
) {}
