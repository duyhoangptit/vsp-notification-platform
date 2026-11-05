package com.pnh.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.coreapi.domain.RecipientValidator;
import com.pnh.provider.RecipientType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import org.junit.jupiter.api.Test;

class RecipientValidatorTest {

    private static void invalid(RecipientType type, String target) {
        assertThatThrownBy(() -> RecipientValidator.normalize(type, target))
                .as("%s %s", type, target)
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RECIPIENT));
    }

    @Test
    void phonesNormalizedToE164WithDefaultRegionVn() {
        assertThat(RecipientValidator.normalize(RecipientType.PHONE, "+84901234567"))
                .isEqualTo("+84901234567");
        assertThat(RecipientValidator.normalize(RecipientType.PHONE, "0901234567"))
                .isEqualTo("+84901234567");
        assertThat(RecipientValidator.normalize(RecipientType.PHONE, " +1 650-253-0000 "))
                .isEqualTo("+16502530000");
        invalid(RecipientType.PHONE, "abc");
        invalid(RecipientType.PHONE, "+8412");
        invalid(RecipientType.PHONE, "");
    }

    @Test
    void emailsValidatedAndLowerCased() {
        assertThat(RecipientValidator.normalize(RecipientType.EMAIL, "User@Example.COM"))
                .isEqualTo("user@example.com");
        invalid(RecipientType.EMAIL, "not-an-email");
        invalid(RecipientType.EMAIL, "a@b");
        invalid(RecipientType.EMAIL, "a".repeat(250) + "@x.com"); // > 254
    }

    @Test
    void deviceTokensNonEmptyAndBounded() {
        assertThat(RecipientValidator.normalize(RecipientType.DEVICE_TOKEN, " tok-123 "))
                .isEqualTo("tok-123");
        assertThat(RecipientValidator.normalize(RecipientType.DEVICE_TOKEN, "t".repeat(4096)))
                .hasSize(4096);
        invalid(RecipientType.DEVICE_TOKEN, "   ");
        invalid(RecipientType.DEVICE_TOKEN, "t".repeat(4097));
        invalid(RecipientType.DEVICE_TOKEN, null);
    }
}
