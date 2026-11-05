package com.pnh.coreapi.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/** POST /api/v1/notifications/send body (client-api SendRequest; snake_case on the wire). */
public record SendRequest(
        @NotBlank @Size(max = 100) String clientRequestId,
        @NotBlank @Size(max = 100) String userId,
        @NotBlank @Size(max = 20) String channelType,
        @NotBlank @Size(max = 4096) String recipientTarget,
        @Size(max = 100) String templateCode,
        Map<String, @Size(max = 2000) String> variables,
        @Pattern(regexp = "STANDARD|OTP") String category,
        @Size(max = 20) String otpValue,
        Instant expiresAt,
        @Valid DirectContent content) {

    @Override
    public String toString() {
        return "SendRequest[" + clientRequestId + ", channel=" + channelType + "]"; // no recipient/variables/OTP
    }
}
