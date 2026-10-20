package com.project.ledgercore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String ownerRef,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code in upper case")
        String currency) {
}