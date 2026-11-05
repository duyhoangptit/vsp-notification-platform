package com.pnh.template.application;

import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import com.pnh.template.RenderedContent;
import com.pnh.template.domain.PlaceholderParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Renders unsaved template content with sample values (T134); SMS segment counting GSM-7 160/153, UCS-2 70/67. */
@Service
public class TemplatePreviewService {

    public record Preview(
            String subject,
            String title,
            String body,
            int characterCount,
            int smsSegments,
            List<String> missingVariables) {}

    private static final String GSM7 =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ!\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
                    + "¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€";

    private final TemplateRenderer renderer;
    private final ChannelRegistry registry;

    public TemplatePreviewService(TemplateRenderer renderer, ChannelRegistry registry) {
        this.renderer = renderer;
        this.registry = registry;
    }

    public Preview preview(
            String channelType, String subject, String title, String body, Map<String, String> variables) {
        ChannelType channel = ChannelType.parseOrNull(channelType);
        if (channel == null || !registry.isRegistered(channel)) {
            throw PnhException.field(
                    ErrorCode.UNSUPPORTED_CHANNEL, "channel_type", "Channel " + channelType + " is not supported");
        }
        boolean html = registry.capabilities(channel).supportsHtml();
        List<String> required = PlaceholderParser.variablesOf(subject, title, body);
        Map<String, String> vars = new HashMap<>(variables == null ? Map.of() : variables);
        List<String> missing = required.stream()
                .filter(v -> vars.get(v) == null || vars.get(v).isBlank())
                .toList();
        for (String m : missing) {
            vars.put(m, html ? "" : "${" + m + "}");
        }
        RenderedContent r = renderer.preview(new TemplateSource("PREVIEW", 0, html, subject, title, body), vars);
        int chars = r.body() == null ? 0 : r.body().length();
        return new Preview(r.subject(), r.title(), r.body(), chars, html ? 0 : segments(r.body()), missing);
    }

    static int segments(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        boolean gsm = text.chars().allMatch(c -> GSM7.indexOf(c) >= 0);
        int single = gsm ? 160 : 70;
        int multi = gsm ? 153 : 67;
        int len = text.length();
        return len <= single ? 1 : (len + multi - 1) / multi;
    }
}
