package com.pnh.template.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import com.pnh.template.domain.PlaceholderParser;
import org.junit.jupiter.api.Test;

class PlaceholderParserTest {

    @Test
    void extractsIdentifiersInOrderWithoutDuplicates() {
        assertThat(PlaceholderParser.variables("Hi ${name}, code ${otp_code}. Bye ${name} ${_x1}"))
                .containsExactly("name", "otp_code", "_x1");
        assertThat(PlaceholderParser.variables("no placeholders")).isEmpty();
        assertThat(PlaceholderParser.variables(null)).isEmpty();
    }

    @Test
    void rejectsExpressionsAndMalformedPlaceholders() {
        for (String bad : new String[] {
            "${T(java.lang.Runtime).getRuntime()}",
            "${a.b}",
            "${1abc}",
            "${}",
            "${ name }",
            "${name",
            "${a-b}",
            "${" + "x".repeat(65) + "}"
        }) {
            assertThatThrownBy(() -> PlaceholderParser.variables("x " + bad + " y"))
                    .as(bad)
                    .isInstanceOfSatisfying(
                            PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PLACEHOLDER));
        }
        assertThat(PlaceholderParser.variables("${" + "x".repeat(64) + "}")).hasSize(1);
    }

    @Test
    void ignoresDollarWithoutBrace() {
        assertThat(PlaceholderParser.variables("Price $5 and {braces}")).isEmpty();
    }
}
