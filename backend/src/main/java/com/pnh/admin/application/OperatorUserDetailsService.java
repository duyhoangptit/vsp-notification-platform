package com.pnh.admin.application;

import com.pnh.admin.adapter.out.OperatorRepository;
import com.pnh.admin.domain.Operator;
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
                .roles(op.getRole().name())
                .disabled(!op.isActive())
                .build();
    }
}
