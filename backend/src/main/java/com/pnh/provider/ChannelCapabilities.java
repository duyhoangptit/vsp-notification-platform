package com.pnh.provider;

/** What a channel adapter can do; intake and the template module validate requests generically against it (R25). */
public record ChannelCapabilities(
        boolean supportsDirectContent,
        boolean requiresExternalTemplate,
        boolean supportsDeliveryCallback,
        boolean supportsHtml,
        int maxContentLength,
        RecipientType recipientType,
        boolean requiresSubject,
        boolean requiresTitle) {

    public static Builder builder(RecipientType recipientType) {
        return new Builder(recipientType);
    }

    public static final class Builder {
        private final RecipientType recipientType;
        private boolean supportsDirectContent = true;
        private boolean requiresExternalTemplate;
        private boolean supportsDeliveryCallback;
        private boolean supportsHtml;
        private int maxContentLength = Integer.MAX_VALUE;
        private boolean requiresSubject;
        private boolean requiresTitle;

        private Builder(RecipientType recipientType) {
            this.recipientType = recipientType;
        }

        public Builder supportsDirectContent(boolean v) {
            this.supportsDirectContent = v;
            return this;
        }

        public Builder requiresExternalTemplate(boolean v) {
            this.requiresExternalTemplate = v;
            return this;
        }

        public Builder supportsDeliveryCallback(boolean v) {
            this.supportsDeliveryCallback = v;
            return this;
        }

        public Builder supportsHtml(boolean v) {
            this.supportsHtml = v;
            return this;
        }

        public Builder maxContentLength(int v) {
            this.maxContentLength = v;
            return this;
        }

        public Builder requiresSubject(boolean v) {
            this.requiresSubject = v;
            return this;
        }

        public Builder requiresTitle(boolean v) {
            this.requiresTitle = v;
            return this;
        }

        public ChannelCapabilities build() {
            return new ChannelCapabilities(
                    supportsDirectContent,
                    requiresExternalTemplate,
                    supportsDeliveryCallback,
                    supportsHtml,
                    maxContentLength,
                    recipientType,
                    requiresSubject,
                    requiresTitle);
        }
    }
}
