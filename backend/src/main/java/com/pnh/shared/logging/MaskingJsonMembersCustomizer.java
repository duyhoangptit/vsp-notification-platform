package com.pnh.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks the {@code message} member of JSON logs (profile {@code container}) with {@link LogSanitizer}, like
 * {@link SensitiveDataMaskingConverter} does for text logs. Logback {@code conversionRule}s do not reach
 * {@code StructuredLogEncoder}, so this is wired via {@code logging.structured.json.customizer}.
 */
public class MaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.applyingValueProcessor(
                ValueProcessor.of(String.class, message -> LogSanitizer.sanitize(message, SensitiveValues.snapshot()))
                        .whenHasPath("message"));
    }
}
