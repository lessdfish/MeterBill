package com.meterbill.account.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 128) String externalKey,
        @NotBlank @Size(max = 160) String name,
        @Email @Size(max = 190) String email,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 64) String timeZoneId) {
}
