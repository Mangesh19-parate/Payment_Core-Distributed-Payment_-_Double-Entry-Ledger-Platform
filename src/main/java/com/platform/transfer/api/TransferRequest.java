package com.platform.transfer.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "principalId is required")
        UUID principalId,

        @NotNull(message = "sourceAccountId is required")
        UUID sourceAccountId,

        @NotNull(message = "destinationAccountId is required")
        UUID destinationAccountId,

        @Min(value = 1, message = "amount must be at least 1 paise")
        @Max(value = 1_000_000_000L, message = "amount exceeds limit of 1 crore paise")
        long amount,

        @NotBlank(message = "currency is required")
        String currency
) {}
