package com.vsp.notification.provider.fake;

import com.vsp.notification.provider.ChannelCapabilities;
import com.vsp.notification.provider.ChannelProvider;
import com.vsp.notification.provider.ConnectionTestResult;
import com.vsp.notification.provider.DeliveryRequest;
import com.vsp.notification.provider.DeliveryResult;
import com.vsp.notification.provider.PermanentProviderException;
import com.vsp.notification.provider.RecipientType;
import com.vsp.notification.provider.TransientProviderException;
import com.vsp.notification.shared.domain.ChannelType;
import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.web.PnhException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only adapter for channel {@code FAKE} (Zalo-readiness proof, R25). */
public class FakeChannelProvider implements ChannelProvider {

    public enum Mode {
        SUCCESS,
        TRANSIENT,
        PERMANENT
    }

    public static final ChannelType FAKE = ChannelType.of("FAKE");

    private volatile Mode mode = Mode.SUCCESS;
    private final List<DeliveryRequest> sent = new CopyOnWriteArrayList<>();
    private final ChannelCapabilities capabilities;

    public FakeChannelProvider() {
        this(ChannelCapabilities.builder(RecipientType.PHONE)
                .maxContentLength(1000)
                .build());
    }

    public FakeChannelProvider(ChannelCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public List<DeliveryRequest> sent() {
        return sent;
    }

    @Override
    public ChannelType channelType() {
        return FAKE;
    }

    @Override
    public String providerKey() {
        return "FAKE_PROVIDER";
    }

    @Override
    public ChannelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public void validateRecipient(String target) {
        if (target == null || !target.startsWith("fake:")) {
            throw new PnhException(ErrorCode.INVALID_RECIPIENT, "FAKE recipients start with 'fake:'");
        }
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        switch (mode) {
            case TRANSIENT -> throw new TransientProviderException("fake transient failure");
            case PERMANENT ->
                throw new PermanentProviderException(ErrorCode.INVALID_RECIPIENT, "fake permanent failure");
            default -> {
                sent.add(request);
                return new DeliveryResult("fake-" + UUID.randomUUID());
            }
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
        return ConnectionTestResult.ok();
    }
}
