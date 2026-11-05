package com.pnh.support;

import com.pnh.tracking.TrackingApi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Integration tests that deliver through the stubbed providers. */
public abstract class StubbedProvidersTestBase extends IntegrationTestBase {

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected TrackingApi tracking;

    @BeforeEach
    void activateStubs() {
        ProviderStubs.activate(data, context);
    }
}
