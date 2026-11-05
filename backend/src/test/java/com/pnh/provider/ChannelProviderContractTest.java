package com.pnh.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract every channel adapter must pass (research R25, T062). Subclasses provide the adapter, a provider stub
 * (WireMock / GreenMail / in-memory) and recipients; the kit checks recipient validation, transient vs permanent
 * error mapping, connection test and declared capabilities.
 */
public abstract class ChannelProviderContractTest {

    protected abstract ChannelProvider provider();

    protected abstract ProviderSettings settings();

    protected abstract String validRecipient();

    protected abstract String invalidRecipient();

    protected abstract void stubSuccess();

    protected abstract void stubTransientFailure();

    protected abstract void stubPermanentFailure();

    /** Error code expected for the stubbed permanent failure. */
    protected ErrorCode permanentErrorCode() {
        return ErrorCode.INVALID_RECIPIENT;
    }

    protected DeliveryRequest request() {
        return new DeliveryRequest(
                UUID.randomUUID(),
                provider().channelType(),
                validRecipient(),
                "Subject",
                "Title",
                "Body text",
                Map.of("k", "v"),
                settings());
    }

    @Test
    void declaresCapabilities() {
        ChannelCapabilities caps = provider().capabilities();
        assertThat(caps).isNotNull();
        assertThat(caps.recipientType()).isNotNull();
        assertThat(caps.maxContentLength()).isPositive();
        assertThat(provider().providerKey()).matches("^[A-Z][A-Z0-9_]{1,49}$");
    }

    @Test
    void validatesRecipients() {
        provider().validateRecipient(validRecipient());
        assertThatThrownBy(() -> provider().validateRecipient(invalidRecipient()))
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RECIPIENT));
    }

    @Test
    void sendsAndReturnsMessageId() {
        stubSuccess();
        DeliveryResult result = provider().send(request());
        assertThat(result).isNotNull();
    }

    @Test
    void mapsTransientFailures() {
        stubTransientFailure();
        assertThatThrownBy(() -> provider().send(request())).isInstanceOf(TransientProviderException.class);
    }

    @Test
    void mapsPermanentFailures() {
        stubPermanentFailure();
        assertThatThrownBy(() -> provider().send(request()))
                .isInstanceOfSatisfying(
                        PermanentProviderException.class,
                        e -> assertThat(e.code()).isEqualTo(permanentErrorCode()));
    }

    @Test
    void connectionTest() {
        stubSuccess();
        assertThat(provider()
                        .testConnection(settings().settings(), settings().secrets())
                        .success())
                .isTrue();
    }
}
