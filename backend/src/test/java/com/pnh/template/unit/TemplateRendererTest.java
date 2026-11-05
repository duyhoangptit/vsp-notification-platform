package com.pnh.template.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.HtmlSanitizerPolicy;
import com.pnh.shared.web.PnhException;
import com.pnh.template.RenderedContent;
import com.pnh.template.application.TemplateRenderer;
import com.pnh.template.application.TemplateSource;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private static TemplateSource email(String subject, String body) {
        return new TemplateSource("T_EMAIL", 1, true, subject, null, body);
    }

    private static TemplateSource sms(String body) {
        return new TemplateSource("T_SMS", 1, false, null, null, body);
    }

    @Test
    void emailHtmlEscapesVariableValues() {
        RenderedContent r = renderer.render(
                email("Hi ${name}", "<p>Hello <b>${name}</b></p>"), Map.of("name", "<script>alert(1)</script>&Co"));
        assertThat(r.body()).isEqualTo("<p>Hello <b>&lt;script&gt;alert(1)&lt;/script&gt;&amp;Co</b></p>");
        assertThat(r.subject()).isEqualTo("Hi <script>alert(1)</script>&Co"); // subject is plain text
    }

    @Test
    void textModeIsVerbatim() {
        RenderedContent r = renderer.render(
                sms("Ma OTP: ${otp_code} <b>${x}</b> & [[raw]]"), Map.of("otp_code", "654321", "x", "1<2"));
        assertThat(r.body()).isEqualTo("Ma OTP: 654321 <b>1<2</b> & [[raw]]");
    }

    @Test
    void extraVariablesIgnored() {
        assertThat(renderer.render(sms("Hi ${a}"), Map.of("a", "1", "unused", "2"))
                        .body())
                .isEqualTo("Hi 1");
    }

    @Test
    void operatorTextCannotInjectThymeleafExpressions() {
        String sneaky = "[[${T(java.lang.Runtime).getRuntime().exec('id')}]] [(${7*7})] [# th:text=\"${7*7}\"/]";
        assertThat(renderer.render(email("s", "<p>" + sneaky + " ${n}</p>"), Map.of("n", "x"))
                        .body())
                .contains("[[")
                .doesNotContain("49");
        assertThatThrownBy(() -> renderer.render(email("s", "<p th:text=\"${7*7}\">x</p>"), Map.of()))
                .isInstanceOfSatisfying(
                        PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PLACEHOLDER));
        assertThat(renderer.render(sms("a [[${7*7}]] [(${7*7})] [# th:utext=\"${7*7}\"/] b"), Map.of())
                        .body())
                .doesNotContain("49");
    }

    @Test
    void attributePlaceholdersRenderValidatedUrls() {
        String body = "<a href=\"${reset_link}\">Đặt lại</a><img src=\"${logo}\" alt=\"Logo ${brand}\">";
        RenderedContent r = renderer.render(
                email("s", body),
                Map.of("reset_link", "https://x/y?t=1&u=2", "logo", "https://cdn/logo.png", "brand", "P\"NH"));
        assertThat(r.body())
                .contains("href=\"https://x/y?t=1&amp;u=2\"")
                .contains("src=\"https://cdn/logo.png\"")
                .contains("alt=\"Logo P&quot;NH\"")
                .contains(">Đặt lại</a>");
    }

    @Test
    void literalPartsOfAttributesAreKept() {
        RenderedContent r = renderer.render(
                email("s", "<a href=\"https://shop.example/orders/${id}?a=1&amp;b=2\">o</a>"), Map.of("id", "42"));
        assertThat(r.body()).contains("href=\"https://shop.example/orders/42?a=1&amp;b=2\"");
    }

    @Test
    void unsafeUrlsRejectedNamingTheVariable() {
        assertThatThrownBy(() -> renderer.render(
                        email("s", "<a href=\"${reset_link}\">x</a>"), Map.of("reset_link", "javascript:alert(1)")))
                .isInstanceOfSatisfying(PnhException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getMessage()).contains("reset_link");
                });
        assertThatThrownBy(() -> renderer.render(
                        email("s", "<img src=\"${logo}\">"), Map.of("logo", "http://insecure/logo.png")))
                .isInstanceOf(PnhException.class);
        assertThat(renderer.render(email("s", "<img src=\"${logo}\">"), Map.of("logo", "data:image/png;base64,AAAA"))
                        .body())
                .contains("src=\"data:image/png;base64,AAAA\"");
        assertThat(renderer.render(email("s", "<a href=\"${m}\">x</a>"), Map.of("m", "mailto:a@b.co"))
                        .body())
                .contains("href=\"mailto:a@b.co\"");
    }

    @Test
    void placeholdersInOtherAttributesRejected() {
        for (String body : new String[] {"<p style=\"color:${c}\">x</p>", "<p onclick=\"${c}\">x</p>"}) {
            assertThatThrownBy(() -> renderer.render(email("s", body), Map.of("c", "red")))
                    .isInstanceOfSatisfying(
                            PnhException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PLACEHOLDER));
        }
    }

    @Test
    void sanitizationRoundTripKeepsPlaceholderAttributesByteForByte() {
        String html = "<p>Hi ${name}</p><a href=\"${reset_link}\">Reset</a><img src=\"${logo}\" />";
        String sanitized = HtmlSanitizerPolicy.sanitizeTemplate(html);
        assertThat(sanitized)
                .contains("href=\"${reset_link}\"")
                .contains("src=\"${logo}\"")
                .contains("${name}");
        String evil = "<p onclick=\"x()\">a<script>b()</script><a href=\"javascript:c()\">l</a></p>";
        assertThat(HtmlSanitizerPolicy.sanitizeTemplate(evil))
                .doesNotContain("onclick")
                .doesNotContain("script")
                .doesNotContain("javascript");
    }
}
