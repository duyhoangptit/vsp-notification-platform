package com.pnh.support;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

/** Decoded text/HTML of a received message (transfer encodings removed). */
public final class MailSupport {

    private MailSupport() {}

    /** Messages received for {@code address} (unique per test → no interference from asynchronous earlier tests). */
    public static java.util.List<MimeMessage> to(String address) {
        java.util.List<MimeMessage> out = new java.util.ArrayList<>();
        for (MimeMessage m : ProviderStubs.GREENMAIL.getReceivedMessages()) {
            try {
                for (jakarta.mail.Address a : m.getAllRecipients()) {
                    if (a.toString().equalsIgnoreCase(address)) {
                        out.add(m);
                    }
                }
            } catch (jakarta.mail.MessagingException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public static MimeMessage awaitOne(String address) {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .until(() -> !to(address).isEmpty());
        return to(address).get(0);
    }

    public static String uniqueAddress() {
        return "u-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    public static String html(MimeMessage message) {
        try {
            String found = find(message);
            return found == null ? "" : found;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String find(Part part) throws Exception {
        if (part.isMimeType("text/*")) {
            return String.valueOf(part.getContent());
        }
        if (part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String s = find(bp);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }
}
