package com.pnh.provider.email;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ChannelProviderContractTest;
import com.pnh.provider.ProviderSettings;
import com.pnh.shared.domain.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.RegisterExtension;

/** T094 */
class SmtpEmailProviderContractTest extends ChannelProviderContractTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    private final SmtpEmailProvider provider = new SmtpEmailProvider();
    private final Map<String, Object> settings = new HashMap<>();
    private Map<String, String> secrets = Map.of();
    private long version;

    @Override
    protected ChannelProvider provider() {
        return provider;
    }

    @Override
    protected ProviderSettings settings() {
        return new ProviderSettings(1, version, settings, secrets);
    }

    @Override
    protected String validRecipient() {
        return "user@example.com";
    }

    @Override
    protected String invalidRecipient() {
        return "not-an-email";
    }

    @Override
    protected void stubSuccess() {
        version++;
        settings.clear();
        settings.putAll(
                Map.of("host", "localhost", "port", greenMail.getSmtp().getPort(), "from", "PNH <no-reply@pnh.test>"));
        secrets = Map.of();
    }

    @Override
    protected void stubTransientFailure() {
        version++;
        settings.clear();
        settings.putAll(Map.of("host", "localhost", "port", 1, "from", "no-reply@pnh.test")); // connection refused
    }

    @Override
    protected void stubPermanentFailure() {
        version++;
        greenMail.setUser("pnh", "right-password");
        greenMail.getManagers().getUserManager().setAuthRequired(true);
        settings.clear();
        settings.putAll(Map.of(
                "host",
                "localhost",
                "port",
                greenMail.getSmtp().getPort(),
                "from",
                "no-reply@pnh.test",
                "username",
                "pnh"));
        secrets = Map.of("password", "wrong-password");
    }

    @Override
    protected ErrorCode permanentErrorCode() {
        return ErrorCode.PROVIDER_AUTH_FAILED;
    }
}
