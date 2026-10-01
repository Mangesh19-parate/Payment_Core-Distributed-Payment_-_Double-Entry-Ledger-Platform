package com.platform.account.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FundAccountRequest(
        UUID principalId,

        @Min(value = 1, message = "amount must be at least 1 paise")
        @Max(value = 1_000_000_000L, message = "amount exceeds limit of 1 crore paise")
        long amount
) {}
