package com.platform.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateAccountRequest(
        @NotNull(message = "ownerId is required")
        UUID ownerId,

        @NotBlank(message = "currency is required")
        String currency
) {}
