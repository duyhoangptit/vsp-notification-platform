package com.pnh.shared.config;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.logging.TraceIds;
import com.pnh.shared.web.ProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Three filter chains (T058, R11/R12): provider callbacks (verified in handlers), client API (stateless HTTP Basic,
 * scopes), operator console (session cookie PNH_SESSION, CSRF cookie XSRF-TOKEN / header X-XSRF-TOKEN, method security).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain callbackChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/callbacks/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain clientApiChain(
            HttpSecurity http,
            @Qualifier("clientAuthenticationProvider") AuthenticationProvider clientAuthenticationProvider)
            throws Exception {
        return http.securityMatcher("/api/v1/**")
                .authenticationManager(new ProviderManager(clientAuthenticationProvider))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/api/v1/notifications/send")
                        .hasAuthority("SCOPE_SEND")
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/track/**")
                        .hasAuthority("SCOPE_TRACK")
                        .requestMatchers("/api/v1/users/**")
                        .hasAuthority("SCOPE_USER_DELEGATE")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/notifications/*/read")
                        .hasAuthority("SCOPE_USER_DELEGATE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications/bulk-read")
                        .hasAuthority("SCOPE_USER_DELEGATE")
                        .anyRequest()
                        .authenticated())
                .httpBasic(b -> b.authenticationEntryPoint((req, res, ex) -> {
                    if (ex instanceof DisabledException) {
                        reject(req, res, ex, ErrorCode.CLIENT_INACTIVE, 403, "Client is inactive");
                    } else {
                        reject(req, res, ex, ErrorCode.UNAUTHORIZED, 401, "Valid client credentials are required");
                    }
                }))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> reject(
                                req, res, ex, ErrorCode.UNAUTHORIZED, 401, "Valid client credentials are required"))
                        .accessDeniedHandler((req, res, ex) -> reject(
                                req,
                                res,
                                ex,
                                ErrorCode.SCOPE_DENIED,
                                403,
                                "Client scope does not allow this operation")))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain consoleChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        return http.securityMatcher("/console/api/**")
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/console/api/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(c ->
                        c.spa().csrfTokenRepository(csrfRepository).ignoringRequestMatchers("/console/api/auth/login"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                                (req, res, ex) -> reject(req, res, ex, ErrorCode.UNAUTHORIZED, 401, "Sign in required"))
                        .accessDeniedHandler((req, res, ex) ->
                                reject(req, res, ex, ErrorCode.FORBIDDEN, 403, "Operation not allowed")))
                .requestCache(r -> r.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .build();
    }

    /** Actuator (health, prometheus only exposed) and the bundled SPA. */
    @Bean
    @Order(4)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .headers(h -> h.contentSecurityPolicy(
                                csp -> csp.policyDirectives(
                                        "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; "
                                                + "script-src 'self'; connect-src 'self'; frame-src 'self'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** Logs an authentication/authorization rejection (these never reach the MVC exception handler), then writes it. */
    static void reject(
            HttpServletRequest req, HttpServletResponse res, Exception ex, ErrorCode code, int status, String detail)
            throws IOException {
        log.warn(
                "Request rejected {} [{}] on {} {}: {} <{}>",
                status,
                code,
                req.getMethod(),
                req.getRequestURI(),
                ex.getMessage(),
                ex.getClass().getSimpleName());
        write(res, code, status, detail, TraceIds.of(req));
    }

    static void write(HttpServletResponse res, ErrorCode code, int status, String detail, String traceId)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(ProblemFactory.toJson(code, status, detail, traceId));
    }
}
