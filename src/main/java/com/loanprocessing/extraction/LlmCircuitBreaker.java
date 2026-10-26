package com.loanprocessing.extraction;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class LlmCircuitBreaker {
    private int consecutiveFailures;
    private Instant openedAt;
    private int threshold = 5;
    private Duration openFor = Duration.ofSeconds(60);

    public synchronized boolean isOpen() {
        if (openedAt == null) {
            return false;
        }
        if (Instant.now().isAfter(openedAt.plus(openFor))) {
            openedAt = null;
            consecutiveFailures = 0;
            return false;
        }
        return true;
    }

    public synchronized boolean holdReached(Duration holdAfter) {
        return openedAt != null && holdAfter != null && Instant.now().isAfter(openedAt.plus(holdAfter));
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openedAt = null;
    }

    public synchronized void recordFailure(int failureThreshold, int openSeconds) {
        this.threshold = failureThreshold;
        this.openFor = Duration.ofSeconds(openSeconds);
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            if (openedAt == null) {
                openedAt = Instant.now();
            }
        }
    }

    public synchronized boolean isCurrentlyOpen() {
        return openedAt != null && Instant.now().isBefore(openedAt.plus(openFor));
    }

    public synchronized void reset() {
        consecutiveFailures = 0;
        openedAt = null;
    }

    public synchronized int state() {
        return isCurrentlyOpen() ? 1 : 0;
    }
}
