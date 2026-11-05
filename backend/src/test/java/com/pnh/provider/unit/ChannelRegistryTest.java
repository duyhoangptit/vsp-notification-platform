package com.pnh.provider.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.provider.ActiveProviderLookup;
import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelRegistry;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelRegistryTest {

    record StubProvider(ChannelType channelType, String providerKey, ChannelCapabilities capabilities)
            implements ChannelProvider {
        @Override
        public void validateRecipient(String target) {}

        @Override
        public DeliveryResult send(DeliveryRequest request) {
            return new DeliveryResult("id");
        }

        @Override
        public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
            return ConnectionTestResult.ok();
        }
    }

    private static final ChannelCapabilities SMS_CAPS = ChannelCapabilities.builder(RecipientType.PHONE)
            .maxContentLength(1600)
            .build();
    private static final ChannelCapabilities SMTP_CAPS =
            ChannelCapabilities.builder(RecipientType.EMAIL).supportsHtml(true).build();

    private final Map<ChannelType, String> active = new java.util.HashMap<>();
    private final ActiveProviderLookup lookup = channel -> Optional.ofNullable(active.get(channel));

    private ChannelRegistry registry() {
        return new ChannelRegistry(
                List.of(
                        new StubProvider(ChannelType.SMS, "GENERIC_HTTP_SMS", SMS_CAPS),
                        new StubProvider(ChannelType.SMS, "ANDROID_SMS_GATEWAY", SMS_CAPS),
                        new StubProvider(ChannelType.EMAIL, "SMTP", SMTP_CAPS)),
                lookup);
    }

    @Test
    void discoversProvidersByChannelAndKey() {
        ChannelRegistry r = registry();
        assertThat(r.isRegistered(ChannelType.SMS)).isTrue();
        assertThat(r.isRegistered(ChannelType.EMAIL)).isTrue();
        assertThat(r.registeredChannels()).containsExactlyInAnyOrder(ChannelType.SMS, ChannelType.EMAIL);
        assertThat(r.provider(ChannelType.SMS, "ANDROID_SMS_GATEWAY")).isPresent();
        assertThat(r.provider(ChannelType.SMS, "SMTP")).isEmpty();
    }

    @Test
    void unknownChannelIsUnsupported() {
        ChannelRegistry r = registry();
        assertThat(r.isRegistered(ChannelType.PUSH)).isFalse();
        assertThatThrownBy(() -> r.activeProvider(ChannelType.PUSH))
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_CHANNEL));
    }

    @Test
    void registeredButNoActiveConfigIsNotConfigured() {
        ChannelRegistry r = registry();
        assertThatThrownBy(() -> r.activeProvider(ChannelType.SMS))
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CHANNEL_NOT_CONFIGURED));
        active.put(ChannelType.SMS, "GENERIC_HTTP_SMS");
        assertThat(r.activeProvider(ChannelType.SMS).providerKey()).isEqualTo("GENERIC_HTTP_SMS");
        // active key that has no registered adapter → not configured
        active.put(ChannelType.EMAIL, "SENDGRID");
        assertThatThrownBy(() -> r.activeProvider(ChannelType.EMAIL))
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CHANNEL_NOT_CONFIGURED));
    }

    @Test
    void duplicateProviderKeyPerChannelFailsFast() {
        assertThatThrownBy(() -> new ChannelRegistry(
                        List.of(
                                new StubProvider(ChannelType.SMS, "GENERIC_HTTP_SMS", SMS_CAPS),
                                new StubProvider(ChannelType.SMS, "GENERIC_HTTP_SMS", SMS_CAPS)),
                        lookup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GENERIC_HTTP_SMS");
    }

    @Test
    void capabilitiesExposedPerChannel() {
        ChannelRegistry r = registry();
        active.put(ChannelType.EMAIL, "SMTP");
        assertThat(r.capabilities(ChannelType.EMAIL).supportsHtml()).isTrue();
        assertThat(r.capabilities(ChannelType.SMS).maxContentLength())
                .isEqualTo(1600); // first registered when none active
        assertThat(r.capabilities(ChannelType.SMS).recipientType()).isEqualTo(RecipientType.PHONE);
    }
}
