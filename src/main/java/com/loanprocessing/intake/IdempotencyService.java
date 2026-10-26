package com.loanprocessing.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.support.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final JdbcGateway gateway;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public IdempotencyService(JdbcGateway gateway, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }

    public Completed execute(String principal, String route, String key, String requestHash, ConfigSnapshot snapshot, Supplier<Completed> work) {
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key must be a UUID");
        }
        int wait = Math.max(snapshot.idempotencyWaitSeconds(), 0);
        Duration inProgress = Duration.ofSeconds(Math.max(wait, 30));
        boolean inserted = Boolean.TRUE.equals(tx.execute(status -> insert(principal, route, key, requestHash, inProgress)));
        if (!inserted) {
            return waitFor(principal, route, key, requestHash, wait);
        }
        try {
            Completed completed = tx.execute(status -> {
                Completed value = work.get();
                long ttl = snapshot.idempotencyTtl() == null ? 86400 : snapshot.idempotencyTtl().getSeconds();
                gateway.jdbc().update("""
                        update idempotency_record
                        set state = 'COMPLETED', http_status = ?, response_body = ?, expires_at = now() + (? * interval '1 second')
                        where customer_principal = ? and route = ? and idempotency_key = ?
                        """, value.status(), gateway.jsonb(value.body().toString()), ttl, principal, route, key);
                return value;
            });
            return completed;
        } catch (RuntimeException e) {
            tx.execute(status -> {
                gateway.jdbc().update("""
                        delete from idempotency_record
                        where customer_principal = ? and route = ? and idempotency_key = ? and state = 'IN_PROGRESS'
                        """, principal, route, key);
                return null;
            });
            throw e;
        }
    }

    private boolean insert(String principal, String route, String key, String requestHash, Duration inProgress) {
        try {
            gateway.jdbc().update("""
                    insert into idempotency_record (customer_principal, route, idempotency_key, request_hash, state, created_at, expires_at)
                    values (?, ?, ?, ?, 'IN_PROGRESS', now(), now() + (? * interval '1 second'))
                    """, principal, route, key, requestHash, inProgress.getSeconds());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Completed waitFor(String principal, String route, String key, String requestHash, int waitSeconds) {
        long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
        while (true) {
            Stored stored = load(principal, route, key);
            if (stored == null) {
                throw new ApiException(409, "IDEMPOTENCY_IN_PROGRESS", "request is already in progress");
            }
            if (!stored.hash().equals(requestHash)) {
                throw new ApiException(409, "IDEMPOTENCY_BODY_MISMATCH", "idempotency key was used with a different request");
            }
            if ("COMPLETED".equals(stored.state())) {
                return new Completed(stored.status(), stored.body());
            }
            if (stored.expired()) {
                tx.execute(status -> {
                    gateway.jdbc().update("""
                            delete from idempotency_record
                            where customer_principal = ? and route = ? and idempotency_key = ? and state = 'IN_PROGRESS' and expires_at < now()
                            """, principal, route, key);
                    return null;
                });
                throw new ApiException(409, "IDEMPOTENCY_IN_PROGRESS", "request is already in progress");
            }
            if (System.nanoTime() >= deadline) {
                throw new ApiException(409, "IDEMPOTENCY_IN_PROGRESS", "request is already in progress");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(409, "IDEMPOTENCY_IN_PROGRESS", "request is already in progress");
            }
        }
    }

    private Stored load(String principal, String route, String key) {
        var rows = gateway.jdbc().query("""
                select request_hash, state, http_status, response_body::text, expires_at < now()
                from idempotency_record
                where customer_principal = ? and route = ? and idempotency_key = ?
                """, (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getInt(3), read(rs.getString(4)), rs.getBoolean(5)), principal, route, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new ApiException(503, "UNAVAILABLE", "stored response could not be read");
        }
    }

    public record Completed(int status, JsonNode body) {
    }

    private record Stored(String hash, String state, int status, JsonNode body, boolean expired) {
    }
}
