package com.pnh.provider.push;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.RecipientType;
import com.pnh.provider.TransientProviderException;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * FCM HTTP v1 adapter (research R14): OAuth2 service-account credentials, {@code POST {base}/v1/projects/{id}/messages:send}.
 * FCM reports acceptance only (SENT is final); UNREGISTERED / INVALID_ARGUMENT → INVALID_RECIPIENT.
 */
@Component
public class FcmPushProvider implements ChannelProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String DEFAULT_BASE = "https://fcm.googleapis.com";

    private final RestClient http;
    private final Map<String, GoogleCredentials> credentials = new ConcurrentHashMap<>();

    public FcmPushProvider() {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        f.setReadTimeout(Duration.ofSeconds(5));
        http = RestClient.builder().requestFactory(f).build();
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.PUSH;
    }

    @Override
    public String providerKey() {
        return "FCM";
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder(RecipientType.DEVICE_TOKEN)
                .requiresTitle(true)
                .maxContentLength(4096)
                .build();
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("service_account_json");
    }

    @Override
    public void validateRecipient(String target) {
        if (target == null || target.isBlank() || target.length() > 4096) {
            throw new PnhException(ErrorCode.INVALID_RECIPIENT, "Device token must be 1..4096 characters");
        }
    }

    @Override
    public Map<String, String> validateSettings(Map<String, Object> settings, Map<String, String> secrets) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (settings.get("project_id") == null
                || String.valueOf(settings.get("project_id")).isBlank()) {
            errors.put("settings.project_id", "project_id is required");
        }
        Object base = settings.get("base_url");
        if (base != null && !String.valueOf(base).matches("^https?://[^\\s]+$")) {
            errors.put("settings.base_url", "base_url must be an absolute http(s) URL");
        }
        String json = secrets == null ? null : secrets.get("service_account_json");
        if (json != null) {
            try {
                ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                errors.put("secrets.service_account_json", "service_account_json is not a valid service account key");
            }
        }
        return errors;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        ProviderSettings cfg = request.config();
        String token = accessToken(cfg);
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", request.title());
        notification.put("body", request.body());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", request.recipient());
        message.put("notification", notification);
        if (request.data() != null && !request.data().isEmpty()) {
            message.put("data", request.data());
        }
        String url =
                cfg.setting("base_url", DEFAULT_BASE) + "/v1/projects/" + cfg.setting("project_id") + "/messages:send";
        try {
            int[] status = new int[1];
            String body = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of("message", message))
                    .exchange((req, response) -> {
                        status[0] = response.getStatusCode().value();
                        return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    });
            if (status[0] < 200 || status[0] >= 300) {
                throw classify(status[0], body);
            }
            return new DeliveryResult(extractName(body));
        } catch (ResourceAccessException e) {
            throw new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "FCM unreachable", e);
        }
    }

    static RuntimeException classify(int status, String body) {
        if (body.contains("UNREGISTERED") || body.contains("INVALID_ARGUMENT") || body.contains("SENDER_ID_MISMATCH")) {
            return new PermanentProviderException(ErrorCode.INVALID_RECIPIENT, "FCM rejected the device token");
        }
        if (status == 401 || status == 403) {
            return new PermanentProviderException(
                    ErrorCode.PROVIDER_AUTH_FAILED, "FCM authentication failed (HTTP " + status + ")");
        }
        if (status == 429 || status >= 500) {
            return new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "FCM HTTP " + status, null);
        }
        return new PermanentProviderException(
                ErrorCode.PROVIDER_REJECTED, "FCM rejected the message (HTTP " + status + ")");
    }

    private static String extractName(String body) {
        int i = body.indexOf("\"name\"");
        if (i < 0) {
            return "fcm";
        }
        int start = body.indexOf('"', body.indexOf(':', i) + 1) + 1;
        return body.substring(start, body.indexOf('"', start));
    }

    private String accessToken(ProviderSettings cfg) {
        GoogleCredentials creds = credentials.computeIfAbsent(
                cfg.configId() + ":" + cfg.version(), k -> load(cfg.secrets(), cfg.settings()));
        try {
            creds.refreshIfExpired();
            AccessToken token = creds.getAccessToken();
            if (token == null) {
                throw new PermanentProviderException(ErrorCode.PROVIDER_AUTH_FAILED, "FCM OAuth token unavailable");
            }
            return token.getTokenValue();
        } catch (IOException e) {
            throw new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "FCM OAuth token fetch failed", e);
        }
    }

    private static GoogleCredentials load(Map<String, String> secrets, Map<String, Object> settings) {
        String json = secrets.get("service_account_json");
        if (json == null) {
            throw new PermanentProviderException(
                    ErrorCode.PROVIDER_AUTH_FAILED, "FCM service_account_json is not configured");
        }
        try {
            ServiceAccountCredentials sa = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            ServiceAccountCredentials.Builder b = sa.toBuilder().setScopes(List.of(SCOPE));
            Object tokenUri = settings.get("token_uri");
            if (tokenUri != null) {
                b.setTokenServerUri(URI.create(String.valueOf(tokenUri)));
            }
            return b.build();
        } catch (IOException e) {
            throw new PermanentProviderException(ErrorCode.PROVIDER_AUTH_FAILED, "FCM service account is invalid", e);
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
        try {
            GoogleCredentials c = load(secrets, settings);
            c.refresh();
            return ConnectionTestResult.ok();
        } catch (IOException | RuntimeException e) {
            return ConnectionTestResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
