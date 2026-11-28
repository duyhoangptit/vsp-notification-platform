package com.vsp.notification.admin.application;

import com.vsp.notification.admin.adapter.out.OperatorRepository;
import com.vsp.notification.admin.domain.Operator;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service("operatorUserDetailsService")
public class OperatorUserDetailsService implements UserDetailsService {

    private final OperatorRepository operators;

    public OperatorUserDetailsService(OperatorRepository operators) {
        this.operators = operators;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Operator op = operators.findById(username).orElseThrow(() -> new UsernameNotFoundException("unknown"));
        return User.withUsername(op.getUsername())
                .password(op.getPasswordHash())
                // ROLE_x for the legacy UI + the identity permissions of that role (what the endpoints check)
                .authorities(java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        "ROLE_" + op.getRole().name()),
                                op.getRole().permissions().stream())
                        .toArray(String[]::new))
                .disabled(!op.isActive())
                .build();
    }
}
