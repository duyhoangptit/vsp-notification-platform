package com.pnh.shared.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Actor = explicit {@link AuditContext} | authenticated principal name (operator / client) | {@code system}. */
@Component("pnhAuditorAware")
public class PnhAuditorAware implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.of(currentActor());
    }

    public static String currentActor() {
        String explicit = AuditContext.current();
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "system";
    }
}
