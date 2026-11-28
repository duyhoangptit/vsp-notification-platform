package com.pnh.shared.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter registered for {@code %msg}/{@code %m}/{@code %message} (text logs) applying {@link LogSanitizer}
 * to the formatted message. JSON logs are masked by {@link MaskingJsonMembersCustomizer}.
 */
public class SensitiveDataMaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.sanitize(event.getFormattedMessage(), SensitiveValues.snapshot());
    }
}
