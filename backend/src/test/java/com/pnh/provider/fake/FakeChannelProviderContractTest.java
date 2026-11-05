package com.pnh.provider.fake;

import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelProviderContractTest;
import com.pnh.provider.ProviderSettings;
import java.util.Map;

/** Proves the contract kit itself with the in-memory adapter. */
class FakeChannelProviderContractTest extends ChannelProviderContractTest {

    private final FakeChannelProvider fake = new FakeChannelProvider();

    @Override
    protected ChannelProvider provider() {
        return fake;
    }

    @Override
    protected ProviderSettings settings() {
        return new ProviderSettings(1, 0, Map.of(), Map.of());
    }

    @Override
    protected String validRecipient() {
        return "fake:alice";
    }

    @Override
    protected String invalidRecipient() {
        return "alice";
    }

    @Override
    protected void stubSuccess() {
        fake.mode(FakeChannelProvider.Mode.SUCCESS);
    }

    @Override
    protected void stubTransientFailure() {
        fake.mode(FakeChannelProvider.Mode.TRANSIENT);
    }

    @Override
    protected void stubPermanentFailure() {
        fake.mode(FakeChannelProvider.Mode.PERMANENT);
    }
}
