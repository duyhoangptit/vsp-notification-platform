package com.pnh.provider.sms;

import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.TransientProviderException;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Helpers shared by SMS adapters. */
final class SmsSupport {

    static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private SmsSupport() {}

    static RestClient restClient() {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    static void validateE164(String target) {
        if (target == null || !E164.matcher(target).matches()) {
            throw new PnhException(ErrorCode.INVALID_RECIPIENT, "Phone number must be in E.164 format");
        }
    }

    /** HTTP status → transient (5xx, 429) / permanent mapping. */
    static RuntimeException classify(int status, String body) {
        String b = body == null ? "" : body;
        if (status >= 500 || status == 429 || status == 408) {
            return new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "SMS gateway HTTP " + status, null);
        }
        if (status == 401 || status == 403) {
            return new PermanentProviderException(
                    ErrorCode.PROVIDER_AUTH_FAILED, "SMS gateway rejected credentials (HTTP " + status + ")");
        }
        if (b.contains("INVALID_NUMBER") || b.contains("INVALID_RECIPIENT")) {
            return new PermanentProviderException(ErrorCode.INVALID_RECIPIENT, "SMS gateway: invalid number");
        }
        return new PermanentProviderException(
                ErrorCode.PROVIDER_REJECTED, "SMS gateway rejected the message (HTTP " + status + ")");
    }

    static RuntimeException io(ResourceAccessException e) {
        ErrorCode code = e.getCause() instanceof java.net.http.HttpTimeoutException
                        || e.getCause() instanceof java.net.SocketTimeoutException
                ? ErrorCode.PROVIDER_TIMEOUT
                : ErrorCode.PROVIDER_UNAVAILABLE;
        return new TransientProviderException(
                code, "SMS gateway unreachable: " + e.getClass().getSimpleName(), e);
    }

    /** Minimal top-level string field extraction (avoids binding a JSON library into the adapter contract). */
    static String jsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String bodyOf(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
