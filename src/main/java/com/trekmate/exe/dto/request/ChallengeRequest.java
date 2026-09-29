package com.trekmate.exe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChallengeRequest(
        @NotBlank @Size(min = 16, max = 16) String userId
) {}
