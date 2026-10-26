package com.loanprocessing.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.credit.CreditCheckService;
import com.loanprocessing.decision.DecisionService;
import com.loanprocessing.extraction.PayslipExtractionPipeline;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ReasonCode;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.OutboxRow;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.model.ActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcGateway gateway;
    private final CreditCheckService credit;
    private final PayslipExtractionPipeline extraction;
    private final ApplicationLifecycle lifecycle;
    private final DecisionService decisions;
    private final RuntimeConfigurationService config;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public OutboxRelay(
            JdbcGateway gateway,
            CreditCheckService credit,
            PayslipExtractionPipeline extraction,
            ApplicationLifecycle lifecycle,
            DecisionService decisions,
            RuntimeConfigurationService config,
            ObjectMapper mapper,
            PlatformTransactionManager manager
    ) {
        this.gateway = gateway;
        this.credit = credit;
        this.extraction = extraction;
        this.lifecycle = lifecycle;
        this.decisions = decisions;
        this.config = config;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }

    public int publishPending() {
        List<OutboxRow> rows = tx.execute(status -> gateway.claimOutbox(20));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (OutboxRow row : rows) {
            handled++;
            try {
                dispatch(row);
                tx.executeWithoutResult(status -> gateway.publish(row.id()));
            } catch (Defer e) {
                tx.executeWithoutResult(status -> gateway.defer(row.id(), row.attempts(), "circuit open", e.seconds()));
            } catch (Exception e) {
                int attempts = row.attempts() + 1;
                int budget = "ExtractionRequested".equals(row.eventType())
                        ? config.current().extractionRetryMax()
                        : config.current().creditRetryMax();
                if (row.aggregateId() == null || attempts >= budget) {
                    tx.executeWithoutResult(status -> {
                        gateway.deadLetter(row, e.getClass().getSimpleName());
                        gateway.audit("DEAD_LETTERED", "outbox", row.id().toString(), "FAILURE", ReasonCode.PROCESSING_POISON.name(), mapper.createObjectNode());
                        if (row.aggregateId() != null) {
                            lifecycle.moveToHold(row.aggregateId(), decisions.holdFor(ReasonCode.PROCESSING_POISON, config.current()));
                        }
                    });
                    log.error("dead_lettered");
                } else {
                    int delay = Math.min(300, 1 << Math.min(attempts, 8));
                    tx.executeWithoutResult(status -> gateway.defer(row.id(), attempts, e.getClass().getSimpleName(), delay));
                    log.warn("outbox_retry");
                }
            }
        }
        return handled;
    }

    private void dispatch(OutboxRow row) throws Exception {
        JsonNode payload = mapper.readTree(row.payload());
        if (payload.get("applicationId") == null || payload.get("applicationId").asText().isBlank()) {
            throw new IllegalArgumentException("poison payload");
        }
        UUID applicationId = UUID.fromString(payload.get("applicationId").asText());
        UUID correlation = row.correlationId() == null ? UUID.randomUUID() : row.correlationId();
        RequestContext.set(new RequestContext.Ids(correlation, UUID.randomUUID().toString(), "outbox", ActorType.SYSTEM));
        try {
            if ("ApplicationSubmitted".equals(row.eventType())) {
                credit.onApplicationSubmitted(applicationId);
            } else if ("ExtractionRequested".equals(row.eventType())) {
                PayslipExtractionPipeline.Result result = extraction.handle(applicationId);
                if (result == PayslipExtractionPipeline.Result.DEFER) {
                    throw new Defer(config.current().circuitOpenSeconds());
                }
                if (result == PayslipExtractionPipeline.Result.FAIL) {
                    throw new IllegalStateException("extraction failed");
                }
            }
        } finally {
            RequestContext.clear();
        }
    }

    private static final class Defer extends RuntimeException {
        private final int seconds;

        private Defer(int seconds) {
            this.seconds = seconds;
        }

        int seconds() {
            return seconds;
        }
    }
}
