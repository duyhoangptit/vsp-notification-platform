package com.pnh.provider.email;

import com.pnh.provider.ChannelCapabilities;
import com.pnh.provider.ChannelProvider;
import com.pnh.provider.ConnectionTestResult;
import com.pnh.provider.DeliveryRequest;
import com.pnh.provider.DeliveryResult;
import com.pnh.provider.PermanentProviderException;
import com.pnh.provider.ProviderSettings;
import com.pnh.provider.RecipientType;
import com.pnh.provider.TransientProviderException;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** SMTP adapter (Mailtrap sandbox / Mailpit; research R14). SMTP has no delivery reports: SENT is final. */
@Component
public class SmtpEmailProvider implements ChannelProvider {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SMTP_CODE = Pattern.compile("\\b([45])\\d\\d\\b");

    private final Map<String, JavaMailSenderImpl> senders = new ConcurrentHashMap<>();

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    @Override
    public String providerKey() {
        return "SMTP";
    }

    @Override
    public ChannelCapabilities capabilities() {
        return ChannelCapabilities.builder(RecipientType.EMAIL)
                .supportsHtml(true)
                .requiresSubject(true)
                .maxContentLength(1_048_576)
                .build();
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("password");
    }

    @Override
    public void validateRecipient(String target) {
        if (target == null || target.length() > 254 || !EMAIL.matcher(target).matches()) {
            throw new PnhException(ErrorCode.INVALID_RECIPIENT, "Email address is not valid");
        }
    }

    @Override
    public Map<String, String> validateSettings(Map<String, Object> settings, Map<String, String> secrets) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (blank(settings.get("host"))) {
            errors.put("settings.host", "host is required");
        }
        try {
            int port = Integer.parseInt(String.valueOf(settings.get("port")));
            if (port < 1 || port > 65535) {
                errors.put("settings.port", "port must be 1..65535");
            }
        } catch (NumberFormatException e) {
            errors.put("settings.port", "port must be a number");
        }
        if (blank(settings.get("from"))) {
            errors.put("settings.from", "from is required");
        } else {
            try {
                new InternetAddress(String.valueOf(settings.get("from")), true);
            } catch (jakarta.mail.internet.AddressException e) {
                errors.put("settings.from", "from must be a valid address");
            }
        }
        return errors;
    }

    @Override
    public DeliveryResult send(DeliveryRequest request) {
        JavaMailSenderImpl sender = sender(request.config());
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(InternetAddress.parse(request.config().setting("from", "no-reply@pnh.local"))[0]);
            helper.setTo(request.recipient());
            helper.setSubject(request.subject() == null ? "" : request.subject());
            helper.setText(request.body(), true);
            message.setHeader("X-PNH-Notification-Id", request.notificationId().toString());
            sender.send(message);
            return new DeliveryResult(message.getMessageID());
        } catch (MailAuthenticationException e) {
            throw new PermanentProviderException(ErrorCode.PROVIDER_AUTH_FAILED, "SMTP authentication failed", e);
        } catch (MailSendException e) {
            throw classify(e);
        } catch (MailException e) {
            throw new TransientProviderException(
                    ErrorCode.PROVIDER_UNAVAILABLE,
                    "SMTP error: " + e.getClass().getSimpleName(),
                    e);
        } catch (MessagingException e) {
            throw new PermanentProviderException(
                    ErrorCode.PROVIDER_REJECTED, "Message could not be built: " + e.getMessage(), e);
        }
    }

    /** 5xx SMTP replies are permanent, 4xx and I/O problems transient. */
    private static RuntimeException classify(MailSendException e) {
        String text = String.valueOf(e.getMessage()) + " "
                + (e.getCause() == null ? "" : e.getCause().toString());
        for (Exception nested : e.getFailedMessages().values()) {
            text += " " + nested;
        }
        Matcher m = SMTP_CODE.matcher(text);
        if (m.find() && m.group(1).equals("5")) {
            return new PermanentProviderException(
                    ErrorCode.PROVIDER_REJECTED, "SMTP rejected the message (" + m.group() + ")", e);
        }
        return new TransientProviderException(ErrorCode.PROVIDER_UNAVAILABLE, "SMTP send failed", e);
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> settings, Map<String, String> secrets) {
        try {
            build(new ProviderSettings(-1, -1, settings, secrets)).testConnection();
            return ConnectionTestResult.ok();
        } catch (MessagingException | RuntimeException e) {
            return ConnectionTestResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private JavaMailSenderImpl sender(ProviderSettings config) {
        return senders.computeIfAbsent(config.configId() + ":" + config.version(), k -> build(config));
    }

    private static JavaMailSenderImpl build(ProviderSettings config) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(config.setting("host"));
        s.setPort(Integer.parseInt(config.setting("port", "25")));
        String user = config.setting("username");
        if (user != null) {
            s.setUsername(user);
            s.setPassword(config.secret("password"));
        }
        Properties p = s.getJavaMailProperties();
        boolean tls = Boolean.parseBoolean(config.setting("tls", "false"));
        p.put("mail.smtp.auth", String.valueOf(user != null));
        p.put("mail.smtp.starttls.enable", String.valueOf(tls));
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "5000");
        p.put("mail.smtp.writetimeout", "5000");
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        return s;
    }

    private static boolean blank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }
}
