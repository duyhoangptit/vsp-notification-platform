package com.pnh.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Allowlist HTML policy for email content (research R28): formatting, tables, links (http/https/mailto), images
 * (https, data:image) and safe inline styles. Scripts, iframes, forms, on* handlers and javascript: URLs are removed.
 */
public final class HtmlSanitizerPolicy {

    private static final Pattern DATA_IMAGE =
            Pattern.compile("^data:image/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=]+$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]{0,63})\\}");
    private static final String TOKEN_PREFIX = "https://pnh.placeholder/";
    private static final Pattern TOKEN = Pattern.compile(Pattern.quote(TOKEN_PREFIX) + "(\\d+)");

    public static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "p",
                    "br",
                    "b",
                    "i",
                    "u",
                    "strong",
                    "em",
                    "span",
                    "div",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "ul",
                    "ol",
                    "li",
                    "table",
                    "thead",
                    "tbody",
                    "tfoot",
                    "tr",
                    "td",
                    "th",
                    "a",
                    "img",
                    "hr",
                    "blockquote",
                    "small",
                    "sup",
                    "sub",
                    "center",
                    "font",
                    "caption",
                    "colgroup",
                    "col",
                    "pre",
                    "code")
            .allowStyling()
            .allowAttributes("href")
            .onElements("a")
            .allowAttributes("target")
            .matching(Pattern.compile("_blank|_self"))
            .onElements("a")
            .allowAttributes("title")
            .globally()
            .allowAttributes("alt", "width", "height")
            .onElements("img")
            .allowAttributes("src")
            .matching((element, name, value) ->
                    value.startsWith("https://") || DATA_IMAGE.matcher(value).matches() ? value : null)
            .onElements("img")
            .allowAttributes(
                    "align",
                    "valign",
                    "width",
                    "height",
                    "colspan",
                    "rowspan",
                    "cellpadding",
                    "cellspacing",
                    "border",
                    "bgcolor")
            .onElements("table", "tr", "td", "th", "div", "p", "col", "colgroup", "center")
            .allowAttributes("color", "face", "size")
            .onElements("font")
            .allowUrlProtocols("http", "https", "mailto", "data")
            .requireRelsOnLinks("noopener", "noreferrer")
            .toFactory();

    private HtmlSanitizerPolicy() {}

    public static String sanitize(String html) {
        return html == null ? null : POLICY.sanitize(html);
    }

    /**
     * Placeholder-safe sanitization for templates (fix H1): every {@code ${…}} is swapped for an opaque https token,
     * the HTML is sanitized, then the tokens are restored — so {@code href="${link}"} survives byte-for-byte.
     */
    public static String sanitizeTemplate(String html) {
        if (html == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(html);
        StringBuilder masked = new StringBuilder();
        while (m.find()) {
            names.add(m.group());
            m.appendReplacement(masked, Matcher.quoteReplacement(TOKEN_PREFIX + (names.size() - 1)));
        }
        m.appendTail(masked);
        String sanitized = POLICY.sanitize(masked.toString());
        Matcher t = TOKEN.matcher(sanitized);
        StringBuilder restored = new StringBuilder();
        while (t.find()) {
            int idx = Integer.parseInt(t.group(1));
            t.appendReplacement(restored, Matcher.quoteReplacement(idx < names.size() ? names.get(idx) : ""));
        }
        t.appendTail(restored);
        return restored.toString();
    }
}
