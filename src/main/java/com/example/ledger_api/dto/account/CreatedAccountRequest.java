package com.example.ledger_api.dto.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatedAccountRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO code")
        String currency
) {
}
