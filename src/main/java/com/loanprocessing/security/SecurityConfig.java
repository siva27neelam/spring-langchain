package com.loanprocessing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${loan.security.mode:header}") String mode) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/actuator/health/**", "/api/v1/admin/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, ex) -> write(response, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((request, response, ex) -> write(response, 403, "FORBIDDEN")));
        if (!"oidc".equals(mode)) {
            http.addFilterBefore((request, response, chain) -> {
                if (!(request instanceof jakarta.servlet.http.HttpServletRequest httpRequest)) {
                    chain.doFilter(request, response);
                    return;
                }
                String header = httpRequest.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    String token = header.substring("Bearer ".length());
                    String[] parts = token.split(":", 2);
                    if (parts.length == 2) {
                        String role = switch (parts[0]) {
                            case "applicant" -> "ROLE_LOAN_APPLICANT";
                            case "operator" -> "ROLE_LOAN_OPERATOR";
                            case "compliance" -> "ROLE_LOAN_COMPLIANCE";
                            case "admin" -> "ROLE_CONFIG_ADMIN";
                            default -> null;
                        };
                        if (role != null) {
                            var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    parts[1], "n/a", List.of(new SimpleGrantedAuthority(role)));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            var current = RequestContext.get();
                            RequestContext.set(new RequestContext.Ids(current.correlationId(), current.requestId(), parts[1], actor(parts[0])));
                        }
                    }
                }
                chain.doFilter(request, response);
            }, BasicAuthenticationFilter.class);
        }
        return http.build();
    }

    private static com.loanprocessing.model.ActorType actor(String role) {
        return switch (role) {
            case "operator" -> com.loanprocessing.model.ActorType.OPERATOR;
            case "admin" -> com.loanprocessing.model.ActorType.CONFIG_ADMIN;
            case "compliance" -> com.loanprocessing.model.ActorType.OPERATOR;
            default -> com.loanprocessing.model.ActorType.CUSTOMER;
        };
    }

    private static void write(HttpServletResponse response, int status, String reason) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        new ObjectMapper().writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank",
                "title", reason,
                "status", status,
                "detail", reason.equals("UNAUTHORIZED") ? "authentication is required" : "forbidden",
                "correlationId", RequestContext.get().correlationId().toString(),
                "reasonCode", reason
        ));
    }
}
