package com.loanprocessing.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.ApplicationRow;
import com.loanprocessing.security.FieldEncryptor;
import com.loanprocessing.security.RateLimiter;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import com.loanprocessing.support.Hashes;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

@Service
public class ApplicationIntakeService {
    private final RuntimeConfigurationService config;
    private final IdempotencyService idempotency;
    private final FieldEncryptor encryptor;
    private final ApplicationLifecycle lifecycle;
    private final JdbcGateway gateway;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;

    public ApplicationIntakeService(
            RuntimeConfigurationService config,
            IdempotencyService idempotency,
            FieldEncryptor encryptor,
            ApplicationLifecycle lifecycle,
            JdbcGateway gateway,
            RateLimiter rateLimiter,
            ObjectMapper mapper
    ) {
        this.config = config;
        this.idempotency = idempotency;
        this.encryptor = encryptor;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
    }

    public IdempotencyService.Completed submit(String principal, String idempotencyKey, ObjectNode body) {
        ConfigSnapshot snapshot = config.current();
        if (!snapshot.valid()) {
            throw new ApiException(503, "CONFIG_INVALID", "configuration is not ready");
        }
        rateLimiter.acquire(principal, "intake", snapshot.intakePerMinute());
        IntakeValidator.Submission submission = IntakeValidator.parse(body, snapshot);
        String hash = Hashes.sha256Hex(Hashes.canonicalJson(body));
        return idempotency.execute(principal, "POST /applications", idempotencyKey, hash, snapshot, () -> persist(principal, submission, snapshot));
    }

    private IdempotencyService.Completed persist(String principal, IntakeValidator.Submission submission, ConfigSnapshot snapshot) {
        if (snapshot.singleOpenApplication()) {
            gateway.jdbc().queryForObject("select pg_advisory_xact_lock(?)", Long.class, (long) java.util.Arrays.hashCode(encryptor.hmac(submission.ssnDigits())));
            Integer open = gateway.jdbc().queryForObject("""
                    select count(*) from loan_application
                    where ssn_hmac = ? and status not in ('APPROVED', 'DECLINED', 'ABANDONED', 'WITHDRAWN')
                    """, Integer.class, encryptor.hmac(submission.ssnDigits()));
            if (open != null && open > 0) {
                throw new ApiException(409, "OPEN_APPLICATION_EXISTS", "an open application already exists");
            }
        }
        ObjectNode address = mapper.createObjectNode();
        address.put("line1", submission.line1());
        address.put("line2", submission.line2());
        address.put("city", submission.city());
        address.put("region", submission.region());
        address.put("postalCode", submission.postalCode());
        address.put("country", submission.country());
        FieldEncryptor.Sealed sealed = encryptor.seal(submission.firstName(), submission.lastName(), submission.ssnDigits(),
                submission.dateOfBirth(), submission.email(), submission.phone(), address);
        UUID id = UUID.randomUUID();
        try {
            ApplicationRow row = new ApplicationRow(id, 0, ApplicationStatus.RECEIVED, null, principal, sealed.wrappedDek(),
                    sealed.keyVersion(), sealed.firstName(), sealed.lastName(), sealed.ssn(), sealed.ssnLast4(), sealed.ssnHmac(),
                    sealed.dob(), sealed.email(), sealed.phone(), sealed.address(), RequestContext.get().correlationId(),
                    null, 0, 0, 0, snapshot.creditRetryMax(), snapshot.extractionRetryMax(), false,
                    OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
            gateway.insertApplication(row);
            lifecycle.recordCreation(row);
            ObjectNode response = mapper.createObjectNode();
            response.put("id", id.toString());
            response.put("status", ApplicationStatus.RECEIVED.name());
            response.putNull("statusReason");
            response.put("maskedSsn", "***-**-" + sealed.ssnLast4());
            ObjectNode links = response.putObject("links");
            links.put("self", "/api/v1/applications/" + id);
            links.put("documents", "/api/v1/applications/" + id + "/documents");
            var created = gateway.find(id).orElseThrow();
            response.put("createdAt", created.createdAt().toString());
            return new IdempotencyService.Completed(201, response);
        } finally {
            Arrays.fill(sealed.dek(), (byte) 0);
        }
    }
}
