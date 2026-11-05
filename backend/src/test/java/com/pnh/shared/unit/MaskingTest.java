package com.pnh.shared.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.Masking;
import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void otpIsFullyMasked() {
        assertThat(Masking.otp("654321")).isEqualTo("******");
        assertThat(Masking.otp("1")).isEqualTo("******");
    }

    @Test
    void emailKeepsFirstCharAndDomain() {
        assertThat(Masking.email("user@example.com")).isEqualTo("u***@example.com");
        assertThat(Masking.email("a@b.co")).isEqualTo("a***@b.co");
        assertThat(Masking.email("not-an-email")).isEqualTo("n***");
    }

    @Test
    void phoneKeepsCountryPrefixAndLastThreeDigits() {
        assertThat(Masking.phone("+84901234678")).isEqualTo("+84*****678");
        assertThat(Masking.phone("12")).isEqualTo("*****");
    }

    @Test
    void tokenIsTruncated() {
        String token = "fcm-token-abcdefghijklmnopqrstuvwxyz-0123456789";
        assertThat(Masking.token(token)).isEqualTo("fcm-toke…6789").doesNotContain("abcdefghij");
        assertThat(Masking.token("short")).isEqualTo("*****");
    }

    @Test
    void recipientMaskedByChannel() {
        assertThat(Masking.recipient(ChannelType.EMAIL, "user@example.com")).isEqualTo("u***@example.com");
        assertThat(Masking.recipient(ChannelType.SMS, "+84901234678")).isEqualTo("+84*****678");
        assertThat(Masking.recipient(ChannelType.of("ZALO"), "+84901234678")).isEqualTo("+84*****678");
        assertThat(Masking.recipient(ChannelType.PUSH, "abcdefghijklmnop")).isEqualTo("abcdefgh…mnop");
        assertThat(Masking.recipient(ChannelType.EMAIL, null)).isNull();
    }

    @Test
    void replaceSecretInText() {
        assertThat(Masking.replace("Your code is 654321, valid 5 minutes. 654321", "654321"))
                .isEqualTo("Your code is ******, valid 5 minutes. ******");
        assertThat(Masking.replace("nothing", "")).isEqualTo("nothing");
        assertThat(Masking.replace(null, "x")).isNull();
    }
}
