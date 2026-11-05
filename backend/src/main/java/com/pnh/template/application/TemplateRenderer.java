package com.pnh.template.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import com.pnh.template.RenderedContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * Renders {@code ${name}} templates with Thymeleaf (research R8) without ever letting operator text be parsed as
 * Thymeleaf: the template is split into literal segments and placeholders; the generated Thymeleaf template consists
 * only of {@code [(${lit[i]})]} (raw literal) and {@code [[${val[i]}]]} (escaped value, HTML) / {@code [(${val[i]})]}
 * (TEXT). Placeholders inside {@code href/src/alt/title} attributes are evaluated in Java and their URLs validated
 * (fix H1). Parsed structures are cached by {@code code:version} (Caffeine {@code templates}, 1 h).
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]{0,63})\\}");
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    private static final Pattern THYMELEAF_ATTR = Pattern.compile("(?i)^(th:|data-th-)");
    private static final Set<String> PLACEHOLDER_ATTRIBUTES = Set.of("href", "src", "alt", "title");
    private static final Pattern DATA_IMAGE = Pattern.compile("(?i)^data:image/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]+$");

    /** A piece of a template: literal text, a variable, or an attribute whose value contains placeholders. */
    sealed interface Segment permits Literal, Variable, Attribute {}

    record Literal(String text) implements Segment {}

    record Variable(String name) implements Segment {}

    record Attribute(String name, String rawValue) implements Segment {}

    private final SpringTemplateEngine engine;
    private final Cache<String, List<Segment>> parsed = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public TemplateRenderer() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setCacheable(true);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setEnableSpringELCompiler(false);
    }

    public RenderedContent render(TemplateSource src, Map<String, String> vars) {
        String prefix = src.code() + ":" + src.version() + ":";
        String subject = src.subject() == null ? null : renderText(prefix + "s", src.subject(), vars);
        String title = src.title() == null ? null : renderText(prefix + "t", src.title(), vars);
        String body =
                src.html() ? renderHtml(prefix + "b", src.body(), vars) : renderText(prefix + "b", src.body(), vars);
        return new RenderedContent(subject, title, body);
    }

    /** Renders an unsaved template (preview) — not cached. */
    public RenderedContent preview(TemplateSource src, Map<String, String> vars) {
        String subject = src.subject() == null ? null : run(parseText(src.subject()), vars, false);
        String title = src.title() == null ? null : run(parseText(src.title()), vars, false);
        String body = src.html() ? run(parseHtml(src.body()), vars, true) : run(parseText(src.body()), vars, false);
        return new RenderedContent(subject, title, body);
    }

    String renderText(String key, String template, Map<String, String> vars) {
        return run(parsed.get(key + ":" + template.hashCode(), k -> parseText(template)), vars, false);
    }

    String renderHtml(String key, String template, Map<String, String> vars) {
        return run(parsed.get(key + ":" + template.hashCode(), k -> parseHtml(template)), vars, true);
    }

    private String run(List<Segment> segments, Map<String, String> vars, boolean html) {
        List<String> literals = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder tpl = new StringBuilder();
        for (Segment s : segments) {
            switch (s) {
                case Literal l -> {
                    tpl.append("[(${lit[").append(literals.size()).append("]})]");
                    literals.add(l.text());
                }
                case Variable v -> {
                    tpl.append(html ? "[[${val[" : "[(${val[")
                            .append(values.size())
                            .append(html ? "]}]]" : "]})]");
                    values.add(vars.getOrDefault(v.name(), ""));
                }
                case Attribute a -> {
                    tpl.append("[(${lit[").append(literals.size()).append("]})]");
                    literals.add(renderAttribute(a, vars));
                }
            }
        }
        Context ctx = new Context(Locale.ROOT);
        ctx.setVariable("lit", literals);
        ctx.setVariable("val", values);
        return engine.process(
                new org.thymeleaf.TemplateSpec(tpl.toString(), html ? TemplateMode.HTML : TemplateMode.TEXT), ctx);
    }

    /** Builds {@code name="escaped value"}; href/src must be safe absolute URLs after substitution. */
    private static String renderAttribute(Attribute a, Map<String, String> vars) {
        String literalUnescaped = a.rawValue();
        StringBuilder value = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(literalUnescaped);
        int last = 0;
        List<String> used = new ArrayList<>();
        while (m.find()) {
            value.append(HtmlUtils.htmlUnescape(literalUnescaped.substring(last, m.start())));
            value.append(vars.getOrDefault(m.group(1), ""));
            used.add(m.group(1));
            last = m.end();
        }
        value.append(HtmlUtils.htmlUnescape(literalUnescaped.substring(last)));
        String v = value.toString().trim();
        String name = a.name().toLowerCase(Locale.ROOT);
        if (name.equals("href") && !isSafeHref(v) || name.equals("src") && !isSafeSrc(v)) {
            throw PnhException.field(
                    ErrorCode.VALIDATION_ERROR,
                    "variables." + String.join(",", used),
                    "Variable " + String.join(", ", used) + " must produce a safe absolute URL for " + name);
        }
        return a.name() + "=\"" + HtmlUtils.htmlEscape(v, "UTF-8") + "\"";
    }

    static boolean isSafeHref(String v) {
        String lower = v.toLowerCase(Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && v.length() > 8 && !v.contains(" ")
                || lower.startsWith("mailto:") && v.length() > 7;
    }

    static boolean isSafeSrc(String v) {
        return v.toLowerCase(Locale.ROOT).startsWith("https://") && v.length() > 8
                || DATA_IMAGE.matcher(v).matches();
    }

    /** Text: literals and valid placeholders; anything else (incl. malformed ${…}) is literal. */
    static List<Segment> parseText(String text) {
        List<Segment> out = new ArrayList<>();
        addText(out, text);
        return out;
    }

    private static void addText(List<Segment> out, String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(new Literal(text.substring(last, m.start())));
            }
            out.add(new Variable(m.group(1)));
            last = m.end();
        }
        if (last < text.length()) {
            out.add(new Literal(text.substring(last)));
        }
    }

    /** HTML: scans tags; placeholders in text become variables, in allowed attributes become attribute segments. */
    static List<Segment> parseHtml(String html) {
        List<Segment> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<' && i + 1 < html.length() && (Character.isLetter(html.charAt(i + 1)))) {
                int end = tagEnd(html, i);
                if (end < 0) {
                    text.append(html.substring(i));
                    break;
                }
                addText(out, text.toString());
                text.setLength(0);
                addTag(out, html.substring(i, end + 1));
                i = end + 1;
            } else {
                text.append(c);
                i++;
            }
        }
        addText(out, text.toString());
        return merge(out);
    }

    private static int tagEnd(String html, int start) {
        char quote = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static void addTag(List<Segment> out, String tag) {
        int nameEnd = 1;
        while (nameEnd < tag.length()
                && !Character.isWhitespace(tag.charAt(nameEnd))
                && tag.charAt(nameEnd) != '>'
                && tag.charAt(nameEnd) != '/') {
            nameEnd++;
        }
        StringBuilder literal = new StringBuilder(tag.substring(0, nameEnd));
        Matcher m = ATTRIBUTE.matcher(tag);
        int pos = nameEnd;
        while (pos < tag.length()) {
            char c = tag.charAt(pos);
            if (Character.isWhitespace(c) || c == '/' || c == '>') {
                literal.append(c);
                pos++;
                continue;
            }
            if (!m.find(pos) || m.start() != pos) {
                literal.append(c);
                pos++;
                continue;
            }
            String name = m.group(1);
            String value = m.group(4) != null ? m.group(4) : m.group(5) != null ? m.group(5) : m.group(6);
            if (THYMELEAF_ATTR.matcher(name).find()) {
                throw PnhException.field(
                        ErrorCode.INVALID_PLACEHOLDER, "body_template", "Attribute " + name + " is not allowed");
            }
            if (value != null && PLACEHOLDER.matcher(value).find()) {
                if (!PLACEHOLDER_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) {
                    throw PnhException.field(
                            ErrorCode.INVALID_PLACEHOLDER,
                            "body_template",
                            "Placeholders are only allowed in text and in href, src, alt or title attributes (found in "
                                    + name + ")");
                }
                out.add(new Literal(literal.toString()));
                literal.setLength(0);
                out.add(new Attribute(name, value));
            } else {
                literal.append(m.group());
            }
            pos = m.end();
        }
        out.add(new Literal(literal.toString()));
    }

    private static List<Segment> merge(List<Segment> in) {
        List<Segment> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (Segment s : in) {
            if (s instanceof Literal l) {
                pending.append(l.text());
            } else {
                if (!pending.isEmpty()) {
                    out.add(new Literal(pending.toString()));
                    pending.setLength(0);
                }
                out.add(s);
            }
        }
        if (!pending.isEmpty()) {
            out.add(new Literal(pending.toString()));
        }
        return out;
    }
}
