package com.pnh.coreapi.application;

import org.springframework.security.authentication.DisabledException;

/** Rendered as 403 CLIENT_INACTIVE by the security entry point. */
public class ClientInactiveException extends DisabledException {
    public ClientInactiveException(String clientId) {
        super("Client " + clientId + " is inactive");
    }
}
