package com.pnh.shared.audit;

/** Thread-bound actor override (e.g. {@code clientId:userId} for preference changes, {@code cli:user}). */
public final class AuditContext {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private AuditContext() {}

    public static <T> T runAs(String actor, java.util.function.Supplier<T> work) {
        String previous = ACTOR.get();
        ACTOR.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                ACTOR.remove();
            } else {
                ACTOR.set(previous);
            }
        }
    }

    static String current() {
        return ACTOR.get();
    }
}
