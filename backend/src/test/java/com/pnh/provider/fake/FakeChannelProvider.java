package com.pnh.provider.fake;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.RecipientType;
import com.pnh.provider.TransientProviderException;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
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
