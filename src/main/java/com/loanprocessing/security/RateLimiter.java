package com.loanprocessing.security;

import com.loanprocessing.support.ApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public void acquire(String principal, String route, int perMinute) {
        if (perMinute <= 0) {
            return;
        }
        String key = principal + "|" + route;
        Deque<Instant> deque = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minusSeconds(60);
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.removeFirst();
            }
            if (deque.size() >= perMinute) {
                throw new ApiException(429, "RATE_LIMIT", "too many requests");
            }
            deque.addLast(Instant.now());
        }
    }

    public void reset() {
        windows.clear();
    }
}
