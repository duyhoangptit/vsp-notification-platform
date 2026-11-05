package com.pnh.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T119 — sample ingest messages vs contracts/kafka/ingest.schema.json (copied to test resources by Maven). */
class IngestMessageContractTest {

    private static boolean valid(String json) {
        return ContractSupport.schemaViolations("ingest.schema.json", json).isEmpty();
    }

    @Test
    void templateAndDirectModes() {
        assertThat(valid("""
                {"client_request_id":"r1","user_id":"u","channel_type":"EMAIL","recipient_target":"a@b.co",
                 "template_code":"OTP_VERIFICATION","variables":{"otp_code":"1","expired_minutes":"5"}}""")).isTrue();
        assertThat(valid("""
                {"client_request_id":"r2","user_id":"u","channel_type":"SMS","recipient_target":"+84901234567",
                 "content":{"body":"hi"}}""")).isTrue();
    }

    @Test
    void exactlyOneOfTemplateOrContent() {
        assertThat(valid("""
                {"client_request_id":"r","user_id":"u","channel_type":"SMS","recipient_target":"+84901234567",
                 "template_code":"X","content":{"body":"hi"}}""")).isFalse();
        assertThat(valid("""
                {"client_request_id":"r","user_id":"u","channel_type":"SMS","recipient_target":"+84901234567"}""")).isFalse();
    }

    @Test
    void directOtpRequiresValueAndExpiry() {
        assertThat(valid("""
                {"client_request_id":"r","user_id":"u","channel_type":"SMS","recipient_target":"+84901234567",
                 "category":"OTP","content":{"body":"code 1"}}""")).isFalse();
        assertThat(valid("""
                {"client_request_id":"r","user_id":"u","channel_type":"SMS","recipient_target":"+84901234567",
                 "category":"OTP","otp_value":"1","expires_at":"2099-01-01T00:00:00Z","content":{"body":"code 1"}}""")).isTrue();
    }

    @Test
    void requiredFields() {
        assertThat(valid("""
                {"user_id":"u","channel_type":"SMS","recipient_target":"+84901234567","content":{"body":"x"}}""")).isFalse();
    }
}
