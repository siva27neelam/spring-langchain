package com.loanprocessing.api;

import com.loanprocessing.model.ActorType;
import com.loanprocessing.security.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (queryLooksUpSsn(request)) {
            response.sendError(400);
            return;
        }
        UUID correlation = parse(request.getHeader("X-Correlation-Id"));
        String requestId = UUID.randomUUID().toString();
        RequestContext.set(new RequestContext.Ids(correlation, requestId, "anonymous", ActorType.CUSTOMER));
        MDC.put("correlationId", correlation.toString());
        MDC.put("requestId", requestId);
        response.setHeader("X-Correlation-Id", correlation.toString());
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
            MDC.put("action", "http_access");
        } finally {
            MDC.clear();
            RequestContext.clear();
        }
    }

    private static boolean queryLooksUpSsn(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null && query.toLowerCase(Locale.ROOT).contains("ssn")) {
            return true;
        }
        var names = request.getParameterNames();
        while (names.hasMoreElements()) {
            if (names.nextElement().toLowerCase(Locale.ROOT).contains("ssn")) {
                return true;
            }
        }
        return false;
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
