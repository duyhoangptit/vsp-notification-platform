package com.pnh.support;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Stand-in for the identity-service JWKS decoder: token {@code staff~<username>~<perm1>+<perm2>} decodes to a staff
 * JWT with those {@code perms} (only RFC 6750 token characters, or Spring rejects it as malformed). Exercises the real console filter chain (bearer filter, CSRF bypass, @PreAuthorize).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStaffJwt {

    public static String bearer(String username, String... perms) {
        return "Bearer staff~" + username + "~" + String.join("+", perms);
    }

    @Bean
    JwtDecoder testStaffJwtDecoder() {
        return token -> {
            String[] parts = token.split("~", 3);
            if (parts.length != 3 || !parts[0].equals("staff")) {
                throw new BadJwtException("not a test staff token");
            }
            List<String> perms = parts[2].isEmpty() ? List.of() : Arrays.asList(parts[2].split("\\+"));
            return Jwt.withTokenValue(token)
                    .header("alg", "ES256")
                    .subject("uid-" + parts[1])
                    .claim("preferred_username", parts[1])
                    .claim("perms", perms)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(900))
                    .build();
        };
    }
}
