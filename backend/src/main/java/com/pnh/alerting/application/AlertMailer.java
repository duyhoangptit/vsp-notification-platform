package com.pnh.alerting.application;

import com.pnh.alerting.adapter.out.AlertRecipientRepository;
import com.pnh.alerting.domain.AlertEvent;
import com.pnh.alerting.domain.AlertRecipient;
import com.pnh.provider.DirectDeliveryApi;
import com.pnh.shared.domain.ChannelType;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Sends incident alerts through the ACTIVE EMAIL provider directly (not the notification pipeline: no opt-out,
 * idempotency or rate limit), with an optional static fallback SMTP ({@code pnh.alerting.fallback-smtp.*}) — T153.
 * Never includes OTPs or full recipient addresses.
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    public record Result(AlertEvent.SendStatus status, String error, List<String> recipients) {}

    private final AlertRecipientRepository recipients;
    private final DirectDeliveryApi direct;
    private final String fallbackHost;
    private final int fallbackPort;
    private final String fallbackFrom;

    public AlertMailer(
            AlertRecipientRepository recipients,
            DirectDeliveryApi direct,
            @Value("${pnh.alerting.fallback-smtp.host:}") String fallbackHost,
            @Value("${pnh.alerting.fallback-smtp.port:25}") int fallbackPort,
            @Value("${pnh.alerting.fallback-smtp.from:pnh-alerts@localhost}") String fallbackFrom) {
        this.recipients = recipients;
        this.direct = direct;
        this.fallbackHost = fallbackHost;
        this.fallbackPort = fallbackPort;
        this.fallbackFrom = fallbackFrom;
    }

    public Result send(String subject, String textBody) {
        List<String> to = recipients.findByActiveTrueOrderByEmailAsc().stream()
                .map(AlertRecipient::getEmail)
                .toList();
        if (to.isEmpty()) {
            return new Result(AlertEvent.SendStatus.NO_RECIPIENTS, null, List.of());
        }
        String html = "<pre style=\"font-family:monospace\">" + HtmlUtils.htmlEscape(textBody) + "</pre>";
        String firstError = null;
        int sent = 0;
        for (String address : to) {
            try {
                direct.send(ChannelType.EMAIL, address, subject, html);
                sent++;
            } catch (RuntimeException e) {
                String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (sendFallback(address, subject, textBody)) {
                    sent++;
                } else if (firstError == null) {
                    firstError = err;
                }
            }
        }
        if (sent == to.size()) {
            return new Result(AlertEvent.SendStatus.SENT, null, to);
        }
        log.warn("Alert email could not be delivered to {} of {} recipients", to.size() - sent, to.size());
        return new Result(AlertEvent.SendStatus.SEND_FAILED, firstError, to);
    }

    private boolean sendFallback(String to, String subject, String body) {
        if (fallbackHost == null || fallbackHost.isBlank()) {
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(fallbackHost);
            sender.setPort(fallbackPort);
            Properties p = sender.getJavaMailProperties();
            p.put("mail.smtp.connectiontimeout", "5000");
            p.put("mail.smtp.timeout", "5000");
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(fallbackFrom);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
