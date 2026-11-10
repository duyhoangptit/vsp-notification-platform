package com.pnh.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pnh.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class SecurityRejectionLoggingTest {

    @Test
    void rejectionIsLoggedAndWritten() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(SecurityConfig.class);
        logger.addAppender(logs);
        try {
            MockHttpServletResponse res = new MockHttpServletResponse();
            SecurityConfig.reject(
                    new MockHttpServletRequest("POST", "/api/v1/notifications/send"),
                    res,
                    new BadCredentialsException("Bad credentials"),
                    ErrorCode.UNAUTHORIZED,
                    401,
                    "Valid client credentials are required");

            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("UNAUTHORIZED");
            assertThat(logs.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage())
                        .contains("401", "UNAUTHORIZED", "POST /api/v1/notifications/send", "BadCredentialsException");
            });
        } finally {
            logger.detachAppender(logs);
        }
    }
}
