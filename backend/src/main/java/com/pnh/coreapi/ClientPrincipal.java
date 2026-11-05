package com.pnh.coreapi;

import com.pnh.coreapi.domain.ClientScope;
import java.io.Serializable;
import java.security.Principal;
import java.util.Set;

/** Authenticated client system (R11). {@link #getName()} = client_id, used as audit actor. */
public record ClientPrincipal(String clientId, Set<ClientScope> scopes, int rateLimitPerMin, boolean allowDirectContent)
        implements Principal, Serializable {

    public ClientPrincipal {
        scopes = Set.copyOf(scopes);
    }

    @Override
    public String getName() {
        return clientId;
    }

    public boolean has(ClientScope scope) {
        return scopes.contains(scope);
    }
}
