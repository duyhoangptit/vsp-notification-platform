package com.pnh.coreapi.application;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.coreapi.adapter.out.ClientRepository;
import com.pnh.coreapi.domain.Client;
import com.pnh.shared.infra.CacheNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * HTTP Basic client authentication (R11): bcrypt verify, successful verifications cached in Caffeine
 * {@code clientAuth} keyed {@code clientId:sha256(secret)} so bcrypt is not paid per request.
 */
@Component("clientAuthenticationProvider")
public class ClientAuthenticationProvider implements AuthenticationProvider {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private final ClientRepository clients;
    private final Cache cache;

    public ClientAuthenticationProvider(ClientRepository clients, CacheManager cacheManager) {
        this.clients = clients;
        this.cache = cacheManager.getCache(CacheNames.CLIENT_AUTH);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String clientId = authentication.getName();
        String secret = authentication.getCredentials() == null
                ? ""
                : authentication.getCredentials().toString();
        String key = clientId + ":" + sha256(secret);
        ClientPrincipal principal = cache.get(key, ClientPrincipal.class);
        if (principal == null) {
            Client client =
                    clients.findById(clientId).orElseThrow(() -> new BadCredentialsException("Bad credentials"));
            if (!BCRYPT.matches(secret, client.getClientSecretHash())) {
                throw new BadCredentialsException("Bad credentials");
            }
            if (!client.isActive()) {
                throw new ClientInactiveException(clientId);
            }
            principal = new ClientPrincipal(
                    client.getClientId(),
                    client.getScopes(),
                    client.getRateLimitPerMin(),
                    client.isAllowDirectContent());
            cache.put(key, principal);
        }
        List<SimpleGrantedAuthority> authorities = principal.scopes().stream()
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s.name()))
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
