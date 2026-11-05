package com.pnh.shared.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback converter ({@code %maskedMsg}) applying {@link LogSanitizer} to the formatted message. */
public class SensitiveDataMaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.sanitize(event.getFormattedMessage(), SensitiveValues.snapshot());
    }
}
