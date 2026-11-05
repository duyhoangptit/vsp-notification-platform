package com.pnh.admin.adapter.in.web;

import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console session login (contracts/console-api.openapi.yaml: /console/api/auth/*). */
@RestController
@RequestMapping("/console/api/auth")
class AuthController {

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    record Me(String username, String displayName, String role) {}

    private final AuthenticationManager operatorAuthenticationManager;
    private final OperatorRepository operators;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    AuthController(
            @Qualifier("operatorAuthenticationManager") AuthenticationManager operatorAuthenticationManager,
            OperatorRepository operators) {
        this.operatorAuthenticationManager = operatorAuthenticationManager;
        this.operators = operators;
    }

    @PostMapping("/login")
    Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = operatorAuthenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            throw new PnhException(
                    ErrorCode.UNAUTHORIZED,
                    401,
                    "Invalid username or password",
                    java.util.List.of(),
                    java.util.Map.of());
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate(); // session fixation protection
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return me(auth.getName());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    Me me(Authentication authentication) {
        return me(authentication.getName());
    }

    private Me me(String username) {
        Operator op = operators.findById(username).orElseThrow(() -> PnhException.of(ErrorCode.UNAUTHORIZED));
        return new Me(op.getUsername(), op.getDisplayName(), op.getRole().name());
    }
}
