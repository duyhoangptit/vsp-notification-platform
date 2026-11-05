package com.pnh.coreapi.adapter.in.kafka;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.coreapi.IngestCommitListener;
import com.pnh.coreapi.adapter.in.web.dto.SendRequest;
import com.pnh.coreapi.adapter.out.ClientRepository;
import com.pnh.coreapi.application.IngestFailureHandler;
import com.pnh.coreapi.application.SubmitNotificationUseCase;
import com.pnh.coreapi.domain.Client;
import com.pnh.shared.crypto.EnvelopeEncryptor;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.KafkaTopics;
import com.pnh.shared.logging.MdcKeys;
import com.pnh.shared.logging.SensitiveValues;
import com.pnh.shared.web.PnhException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka ingestion (T122, US4): same semantics as REST send. The client is identified by the {@code client_id} header
 * and authenticated by {@code pnh-signature}; the offset is acknowledged only after the intake transaction committed.
 */
@Component
class IngestListener {

    private final ClientRepository clients;
    private final EnvelopeEncryptor encryptor;
    private final IngestSignatureVerifier signatures;
    private final SubmitNotificationUseCase useCase;
    private final IngestFailureHandler failures;
    private final Validator validator;
    private final JsonMapper json;
    private final List<IngestCommitListener> commitListeners;
    private final boolean signatureRequired;

    IngestListener(
            ClientRepository clients,
            EnvelopeEncryptor encryptor,
            IngestSignatureVerifier signatures,
            SubmitNotificationUseCase useCase,
            IngestFailureHandler failures,
            Validator validator,
            JsonMapper json,
            List<IngestCommitListener> commitListeners,
            @Value("${pnh.kafka.signature-required:true}") boolean signatureRequired) {
        this.clients = clients;
        this.encryptor = encryptor;
        this.signatures = signatures;
        this.useCase = useCase;
        this.failures = failures;
        this.validator = validator;
        this.json = json;
        this.commitListeners = commitListeners;
        this.signatureRequired = signatureRequired;
    }

    @KafkaListener(
            id = "pnh-ingest",
            topics = KafkaTopics.INGEST,
            containerFactory = "ingestListenerFactory",
            autoStartup = "${pnh.kafka.listeners-enabled:true}")
    void onStandard(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack);
    }

    @KafkaListener(
            id = "pnh-ingest-otp",
            topics = KafkaTopics.INGEST_OTP,
            containerFactory = "ingestListenerFactory",
            concurrency = "${pnh.kafka.otp-concurrency:6}",
            autoStartup = "${pnh.kafka.listeners-enabled:true}")
    void onOtp(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack);
    }

    void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            process(record);
            ack.acknowledge();
        } finally {
            MDC.remove(MdcKeys.CLIENT_ID);
            MDC.remove(MdcKeys.CLIENT_REQUEST_ID);
            MDC.remove(MdcKeys.NOTIFICATION_ID);
            SensitiveValues.clear();
        }
    }

    private void process(ConsumerRecord<String, String> record) {
        String clientId = header(record, KafkaTopics.HEADER_CLIENT_ID);
        Optional<Client> client = clientId == null ? Optional.empty() : clients.findById(clientId);
        if (client.isEmpty() || !client.get().isActive()) {
            failures.toDlt(record, clientId, ErrorCode.UNAUTHORIZED, "Unknown or inactive client");
            return;
        }
        byte[] value = record.value() == null ? new byte[0] : record.value().getBytes(StandardCharsets.UTF_8);
        JsonNode node;
        try {
            node = json.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("not an object");
            }
        } catch (RuntimeException e) {
            failures.toDlt(record, clientId, ErrorCode.VALIDATION_ERROR, "Message is not a JSON object");
            return;
        }
        JsonNode rid = node.get("client_request_id");
        String clientRequestId = rid != null && rid.isString() ? rid.asString() : null;
        if (signatureRequired
                && !verified(client.get(), clientRequestId, value, header(record, KafkaTopics.HEADER_SIGNATURE))) {
            failures.toDlt(record, clientId, ErrorCode.UNAUTHORIZED, "Missing or invalid pnh-signature");
            return;
        }
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 100) {
            failures.toDlt(record, clientId, ErrorCode.VALIDATION_ERROR, "client_request_id is missing or invalid");
            return;
        }
        MDC.put(MdcKeys.CLIENT_ID, clientId);
        MDC.put(MdcKeys.CLIENT_REQUEST_ID, clientRequestId);
        SendRequest request;
        try {
            request = json.treeToValue(node, SendRequest.class);
        } catch (RuntimeException e) {
            failures.failReadable(
                    clientId,
                    partial(node, clientRequestId),
                    ErrorCode.VALIDATION_ERROR,
                    "Message does not match the ingest schema");
            return;
        }
        Set<ConstraintViolation<SendRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            ConstraintViolation<SendRequest> v = violations.iterator().next();
            failures.failReadable(
                    clientId,
                    request,
                    ErrorCode.VALIDATION_ERROR,
                    snake(v.getPropertyPath().toString()) + ": " + v.getMessage());
            return;
        }
        Client c = client.get();
        ClientPrincipal principal =
                new ClientPrincipal(c.getClientId(), c.getScopes(), c.getRateLimitPerMin(), c.isAllowDirectContent());
        try {
            useCase.accept(request, principal);
        } catch (PnhException e) {
            failures.failReadable(clientId, request, e.code(), e.getMessage());
            return;
        }
        for (IngestCommitListener l : commitListeners) {
            l.afterIntakeCommitted(clientRequestId);
        }
    }

    private boolean verified(Client client, String clientRequestId, byte[] value, String signature) {
        if (client.getKafkaSigningKeyEncrypted() == null) {
            return false;
        }
        String key = encryptor.decryptString(client.getKafkaSigningKeyEncrypted());
        return signatures.verify(key, client.getClientId(), clientRequestId, value, signature);
    }

    /** Best-effort request with the readable string fields only (for FAILED records of malformed messages). */
    private SendRequest partial(JsonNode node, String clientRequestId) {
        return new SendRequest(
                clientRequestId,
                text(node, "user_id"),
                text(node, "channel_type"),
                text(node, "recipient_target"),
                text(node, "template_code"),
                null,
                text(node, "category"),
                null,
                null,
                null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static String snake(String path) {
        return path.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
