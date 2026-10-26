package com.loanprocessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.config.ConfigCatalog;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.document.ScriptedMalwareScanAdapter;
import com.loanprocessing.extraction.LlmCircuitBreaker;
import com.loanprocessing.extraction.ScriptedChatModel;
import com.loanprocessing.jobs.LeaseAndAbandonJobs;
import com.loanprocessing.model.ComparisonOperator;
import com.loanprocessing.outbox.OutboxRelay;
import com.loanprocessing.security.RateLimiter;
import com.loanprocessing.support.FaultSwitch;
import com.loanprocessing.support.Synthetic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoanProcessingFlowTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger(20);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RuntimeConfigurationService config;
    @Autowired private OutboxRelay relay;
    @Autowired private ScriptedChatModel scripted;
    @Autowired private ScriptedMalwareScanAdapter scanner;
    @Autowired private FaultSwitch faults;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private LlmCircuitBreaker circuit;
    @Autowired private LeaseAndAbandonJobs jobs;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE
                    integrity_report, scheduler_lease, config_change_audit, config_change_proposal,
                    audit_event, idempotency_record, outbox_dead_letter, outbox, decision, extraction_field,
                    extraction_run, document, pending_object, credit_report, credit_inquiry,
                    application_status_history, loan_application
                RESTART IDENTITY CASCADE
                """);
        for (var entry : ConfigCatalog.defaults().entrySet()) {
            jdbc.update("update runtime_config set value_json = ?::jsonb, version = 1, updated_by = 'seed' where key = ?",
                    entry.getValue(), entry.getKey());
        }
        jdbc.update("update config_generation set generation = 1");
        config.loadOrThrow();
        scripted.reset();
        scanner.reset();
        faults.reset();
        rateLimiter.reset();
        circuit.reset();
        Path root = Path.of("target/test-objects");
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // the next test recreates the storage root
                    }
                });
            }
        }
    }

    @Test
    void seedMatchesConfirmedDefaults() {
        assertEquals(0, config.current().salaryThreshold().compareTo(new java.math.BigDecimal("7000")));
        assertEquals(ComparisonOperator.GREATER_THAN, config.current().salaryComparison());
        assertEquals(600, config.current().stubScore());
        assertFalse(config.current().cutoffEnabled());
        assertEquals(1, config.current().requiredPayslips());
        assertEquals("P14D", config.current().abandonAfter().toString());
    }

    @Test
    void applicationExistsBeforeTheStubPullAndScore600ContinuesToPayslipReview() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String digits = Synthetic.digits(n);
        UUID id = submit(n, "Applicant");
        assertEquals("RECEIVED", statusOf(id));
        assertEquals(0, count("select count(*) from credit_report where application_id = ?", id));
        String payload = jdbc.queryForObject("select payload::text from outbox where aggregate_id = ?", String.class, id);
        assertFalse(payload.contains(digits));
        assertTrue(payload.contains(id.toString()));
        relay.publishPending();
        assertEquals("PAYSLIP_REVIEW", statusOf(id));
        assertEquals(600, jdbc.queryForObject("select score from credit_report where application_id = ?", Integer.class, id));
        assertEquals("STUB", jdbc.queryForObject("select provider from credit_report where application_id = ?", String.class, id));
        assertEquals("STUB_SCORE", jdbc.queryForObject("select score_scale from credit_report where application_id = ?", String.class, id));
        Timestamp created = jdbc.queryForObject("select created_at from loan_application where id = ?", Timestamp.class, id);
        Timestamp pulled = jdbc.queryForObject("select pulled_at from credit_report where application_id = ?", Timestamp.class, id);
        assertFalse(pulled.before(created));
        String response = jdbc.queryForObject("select response_body::text from idempotency_record", String.class);
        assertFalse(response.contains(digits));
        mockMvc.perform(get("/api/v1/applications/" + id).header("Authorization", bearer(n)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditScore").value(600))
                .andExpect(jsonPath("$.maskedSsn").value("***-**-" + digits.substring(5)));
        mockMvc.perform(get("/api/v1/applications/" + id).header("Authorization", "Bearer applicant:other-person"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/applications").param("ssn", "1").header("Authorization", bearer(n)))
                .andExpect(status().isBadRequest());
        assertThrows(DataAccessException.class, () -> jdbc.update("update audit_event set outcome = 'FAILURE'"));
    }

    @Test
    void monthlySalaryAtAndUnder7000ApprovesAndACentOverDeclines() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        UUID under = decide("Applicant", "6999.99");
        UUID equal = decide("Applicant", "7000.00");
        UUID over = decide("Applicant", "7000.01");
        assertEquals("APPROVED", statusOf(under));
        assertEquals("APPROVED", statusOf(equal));
        assertEquals("DECLINED", statusOf(over));
        assertEquals("SALARY_RULE", ruleOf(over));
        Long generation = jdbc.queryForObject("select config_generation from decision where application_id = ?", Long.class, equal);
        assertEquals(config.current().generation(), generation);
        mockMvc.perform(post("/api/v1/applications/" + equal + "/withdrawal").header("Authorization", bearerOf(equal)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("TERMINAL_STATUS"));
    }

    @Test
    void groundedMismatchDeclinesAndStopsFurtherUploads() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        UUID id = decide("Otherperson", "1000.00");
        assertEquals("DECLINED", statusOf(id));
        assertEquals("IDENTITY_MISMATCH", ruleOf(id));
        mockMvc.perform(multipart("/api/v1/applications/" + id + "/documents")
                        .file(pdf("payslip-2.pdf", Synthetic.payslip(Synthetic.payslipLines("Applicant", "1000.00"))))
                        .header("Authorization", bearerOf(id))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("STATUS_NOT_ACCEPTING_DOCUMENTS"));
    }

    @Test
    void uncertainExtractionAndPromptInjectionNeverApprove() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        scripted.setHandler(text -> "{\"outcome\":\"APPROVED\",\"grossAmount\":\"100\",\"employeeLastName\":\"Applicant\"}");
        UUID id = throughPayslip("Applicant", "1000.00");
        relay.publishPending();
        assertEquals("MANUAL_HOLD", statusOf(id));
        assertNotEquals("APPROVED", statusOf(id));
        assertEquals(0, count("select count(*) from decision where application_id = ? and outcome = 'APPROVED'", id));
    }

    @Test
    void unconfiguredModelHoldsWithoutCallingTheModel() throws Exception {
        UUID id = throughPayslip("Applicant", "1000.00");
        relay.publishPending();
        assertEquals("MANUAL_HOLD", statusOf(id));
        assertEquals("MODEL_NOT_CONFIGURED", jdbc.queryForObject(
                "select status_reason from loan_application where id = ?", String.class, id));
        assertEquals(0, scripted.calls());
    }

    @Test
    void modelInputIsRedactedBeforeTheCall() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        int n = SEQUENCE.incrementAndGet();
        String dashed = Synthetic.dashed(n);
        AtomicReference<String> seen = new AtomicReference<>();
        scripted.setHandler(text -> {
            seen.set(text);
            return ScriptedChatModel.echo(text);
        });
        UUID id = submit(n, "Applicant");
        relay.publishPending();
        String[] lines = Synthetic.payslipLines("Applicant", "1000.00");
        String[] withNumber = new String[lines.length + 1];
        System.arraycopy(lines, 0, withNumber, 0, lines.length);
        withNumber[lines.length] = "note: " + dashed;
        upload(id, n, Synthetic.payslip(withNumber), UUID.randomUUID());
        relay.publishPending();
        assertTrue(seen.get() != null && !seen.get().contains(dashed), seen.get());
        assertFalse(seen.get().contains(dashed.replace("-", "")));
    }

    @Test
    void lostLeaseDoesNotApprove() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        UUID id = throughPayslip("Applicant", "1000.00");
        scripted.setHandler(text -> {
            jdbc.update("update loan_application set lease_generation = lease_generation + 50 where id = ?", id);
            return ScriptedChatModel.echo(text);
        });
        relay.publishPending();
        assertNotEquals("APPROVED", statusOf(id));
        assertEquals(1, count("select count(*) from extraction_run where application_id = ? and disposition = 'DISCARDED'", id));
    }

    @Test
    void withdrawalDuringExtractionDoesNotApprove() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        UUID id = throughPayslip("Applicant", "1000.00");
        scripted.setHandler(text -> {
            jdbc.update("update loan_application set withdraw_requested = true where id = ?", id);
            return ScriptedChatModel.echo(text);
        });
        relay.publishPending();
        assertEquals("WITHDRAWN", statusOf(id));
        assertEquals(0, count("select count(*) from decision where application_id = ? and outcome = 'APPROVED'", id));
    }

    @Test
    void autoDecisionDisabledHoldsACertainMatch() throws Exception {
        publishSensitive("llm.model", mapper.getNodeFactory().textNode("test-model"));
        publishSensitive("extraction.auto-decision.enabled", mapper.getNodeFactory().booleanNode(false));
        UUID id = decide("Applicant", "1000.00");
        assertEquals("MANUAL_HOLD", statusOf(id));
        assertEquals("AUTO_DECISION_DISABLED", jdbc.queryForObject(
                "select status_reason from loan_application where id = ?", String.class, id));
    }

    @Test
    void cutoffDeclineStoresTheScoreAndADecisionRow() throws Exception {
        publishSensitive("credit.cutoff.minimum-score", mapper.getNodeFactory().numberNode(650));
        publishSensitive("credit.cutoff.enabled", mapper.getNodeFactory().booleanNode(true));
        UUID id = submit(SEQUENCE.incrementAndGet(), "Applicant");
        relay.publishPending();
        assertEquals("DECLINED", statusOf(id));
        assertEquals("CREDIT_BELOW_CUTOFF", ruleOf(id));
        assertEquals(600, jdbc.queryForObject("select score from credit_report where application_id = ?", Integer.class, id));
    }

    @Test
    void fourEyesRejectsSelfApprovalAndAnApproveUncertaintyAction() throws Exception {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("key", "decision.salary.threshold");
        proposal.put("value", 1);
        proposal.put("reason", "attempted self approval");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/runtime-config/proposals")
                        .header("Authorization", "Bearer admin:admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal.toString()))
                .andExpect(status().isOk())
                .andReturn();
        String proposalId = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/admin/runtime-config/proposals/" + proposalId + "/approval")
                        .header("Authorization", "Bearer admin:admin-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("FOUR_EYES"));
        assertEquals(0, config.current().salaryThreshold().compareTo(new java.math.BigDecimal("7000")));

        ObjectNode bad = mapper.createObjectNode();
        bad.put("key", "decision.uncertainty.action");
        bad.put("value", "APPROVED");
        bad.put("reason", "must be rejected");
        mockMvc.perform(post("/api/v1/admin/runtime-config/proposals")
                        .header("Authorization", "Bearer admin:admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("CONFIG_INVALID"));
    }

    @Test
    void operatorCanResolveManualHoldAndCannotApproveFromPayslipReview() throws Exception {
        UUID waiting = throughPayslip("Applicant", "1000.00");
        mockMvc.perform(post("/api/v1/admin/applications/" + waiting + "/manual-decisions")
                        .header("Authorization", "Bearer operator:operator-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"reasonCode\":\"OPERATOR_POLICY\",\"comment\":\"synthetic review\"}"))
                .andExpect(status().isConflict());
        UUID held = throughPayslip("Applicant", "1000.00");
        relay.publishPending();
        assertEquals("MANUAL_HOLD", statusOf(held));
        mockMvc.perform(post("/api/v1/admin/applications/" + held + "/manual-decisions")
                        .header("Authorization", "Bearer operator:operator-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"reasonCode\":\"OPERATOR_POLICY\",\"comment\":\"synthetic review\"}"))
                .andExpect(status().isOk());
        assertEquals("APPROVED", statusOf(held));
        assertEquals("OPERATOR", jdbc.queryForObject("select source from decision where application_id = ? and outcome = 'APPROVED'", String.class, held));
    }

    @Test
    void auditFailureRollsBackTheApplication() throws Exception {
        faults.setAuditFail(true);
        int n = SEQUENCE.incrementAndGet();
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(application(n, "Applicant").toString()))
                .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_application", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_event", Integer.class));
    }

    @Test
    void illegalIdentityIsRejectedWithoutARow() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        ObjectNode illegal = application(n, "Applicant");
        illegal.put("ssn", Synthetic.illegalDashed("000", "00", "0000"));
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(illegal.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("VALIDATION"));
        ObjectNode young = application(n, "Applicant");
        young.put("dateOfBirth", LocalDate.now(ZoneOffset.UTC).minusYears(10).toString());
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(young.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("BELOW_MIN_AGE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from loan_application", Integer.class));
        mockMvc.perform(post("/api/v1/applications")).andExpect(status().isUnauthorized());
    }

    @Test
    void idempotencyReplaysTheSameBodyAndRejectsADifferentOne() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String key = UUID.randomUUID().toString();
        String first = application(n, "Applicant").toString();
        MvcResult created = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
        ObjectNode changed = application(n, "Applicant");
        changed.put("firstName", "Tester");
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("IDEMPOTENCY_BODY_MISMATCH"));
    }

    @Test
    void duplicatePayslipReplaysWhileASecondFileIsStillRequired() throws Exception {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("key", "documents.payslip.required-count");
        proposal.put("value", 2);
        proposal.put("reason", "allow a replay before the required count");
        mockMvc.perform(post("/api/v1/admin/runtime-config/proposals")
                        .header("Authorization", "Bearer admin:admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposal.toString()))
                .andExpect(status().isOk());
        config.loadOrThrow();
        assertEquals(2, config.current().requiredPayslips());
        int n = SEQUENCE.incrementAndGet();
        UUID id = submit(n, "Applicant");
        relay.publishPending();
        byte[] pdf = Synthetic.payslip(Synthetic.payslipLines("Applicant", "1000.00"));
        upload(id, n, pdf, UUID.randomUUID());
        mockMvc.perform(multipart("/api/v1/applications/" + id + "/documents")
                        .file(pdf("payslip.pdf", pdf))
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
        assertEquals("PAYSLIP_REVIEW", statusOf(id));
    }

    @Test
    void infectedUploadIsRejectedAndADownScannerDoesNotMarkTheFileClean() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        UUID id = submit(n, "Applicant");
        relay.publishPending();
        assertEquals("PAYSLIP_REVIEW", statusOf(id));
        scanner.setMode(ScriptedMalwareScanAdapter.Mode.INFECTED);
        mockMvc.perform(multipart("/api/v1/applications/" + id + "/documents")
                        .file(pdf("payslip.pdf", Synthetic.payslip(Synthetic.payslipLines("Applicant", "1000.00"))))
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("MALWARE"));
        assertEquals(1, count("select count(*) from document where application_id = ? and status = 'REJECTED'", id));
        assertEquals("PAYSLIP_REVIEW", statusOf(id));
        scanner.setMode(ScriptedMalwareScanAdapter.Mode.DOWN);
        mockMvc.perform(multipart("/api/v1/applications/" + id + "/documents")
                        .file(pdf("payslip-2.pdf", Synthetic.payslip(Synthetic.payslipLines("Applicant", "1100.00"))))
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reasonCode").value("SCANNER_UNAVAILABLE"));
        assertEquals(0, count("select count(*) from document where application_id = ? and malware_scan = 'CLEAN'", id));
    }

    @Test
    void abandonedPayslipReviewDoesNotApprove() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        UUID id = submit(n, "Applicant");
        relay.publishPending();
        assertEquals("PAYSLIP_REVIEW", statusOf(id));
        jdbc.update("update loan_application set last_activity_at = now() - interval '15 days' where id = ?", id);
        assertTrue(jobs.abandon() >= 1);
        assertEquals("ABANDONED", statusOf(id));
    }

    @Test
    void integrityJobFlagsAnApprovalWithoutADecision() throws Exception {
        UUID id = throughPayslip("Applicant", "1000.00");
        jdbc.update("update loan_application set status = 'APPROVED' where id = ?", id);
        assertTrue(jobs.integrity() >= 1);
    }

    @Test
    void breakGlassRevealAuditsTheFieldAndNotTheValueInMetadata() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String digits = Synthetic.digits(n);
        UUID id = submit(n, "Applicant");
        MvcResult revealed = mockMvc.perform(post("/api/v1/admin/applications/" + id + "/reveal")
                        .header("Authorization", "Bearer compliance:compliance-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"ssn\",\"reason\":\"synthetic compliance review\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(digits, mapper.readTree(revealed.getResponse().getContentAsString()).get("value").asText());
        String metadata = jdbc.queryForObject(
                "select metadata::text from audit_event where action = 'BREAK_GLASS_REVEAL'", String.class);
        assertFalse(metadata.contains(digits));
        assertTrue(metadata.contains("ssn"));
    }

    private UUID decide(String payslipLastName, String gross) throws Exception {
        UUID id = throughPayslip(payslipLastName, gross);
        relay.publishPending();
        return id;
    }

    private UUID throughPayslip(String payslipLastName, String gross) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        UUID id = submit(n, "Applicant");
        relay.publishPending();
        assertEquals("PAYSLIP_REVIEW", statusOf(id), "credit did not reach payslip review");
        upload(id, n, Synthetic.payslip(Synthetic.payslipLines(payslipLastName, gross)), UUID.randomUUID());
        return id;
    }

    private UUID submit(int n, String lastName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(application(n, lastName).toString()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertFalse(body.toString().contains(Synthetic.digits(n)));
        return UUID.fromString(body.get("id").asText());
    }

    private void upload(UUID id, int n, byte[] pdf, UUID key) throws Exception {
        mockMvc.perform(multipart("/api/v1/applications/" + id + "/documents")
                        .file(pdf("payslip.pdf", pdf))
                        .header("Authorization", bearer(n))
                        .header("Idempotency-Key", key.toString()))
                .andExpect(status().isCreated());
    }

    private void publishSensitive(String key, JsonNode value) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("key", key);
        body.set("value", value);
        body.put("reason", "test fixture");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/runtime-config/proposals")
                        .header("Authorization", "Bearer admin:admin-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        if (!ConfigCatalog.sensitive(key)) {
            config.loadOrThrow();
            return;
        }
        String proposalId = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/admin/runtime-config/proposals/" + proposalId + "/approval")
                        .header("Authorization", "Bearer admin:admin-b")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private ObjectNode application(int n, String lastName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("firstName", "Test");
        body.put("lastName", lastName);
        body.put("ssn", Synthetic.dashed(n));
        body.put("dateOfBirth", Synthetic.adultBirthDate().toString());
        body.put("email", "applicant-" + n + "@example.test");
        body.put("phone", "+1202555" + String.format("%04d", 1000 + Math.floorMod(n, 8000)));
        ObjectNode address = body.putObject("address");
        address.put("line1", "10 Test Street");
        address.put("city", "Testville");
        address.put("region", "CA");
        address.put("postalCode", "94105");
        address.put("country", "US");
        return body;
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from loan_application where id = ?", String.class, id);
    }

    private String ruleOf(UUID id) {
        return jdbc.queryForObject("select rule_code from decision where application_id = ? order by created_at desc limit 1", String.class, id);
    }

    private int count(String sql, UUID id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count == null ? 0 : count;
    }

    private String bearer(int n) {
        return "Bearer applicant:applicant-" + n;
    }

    private String bearerOf(UUID id) {
        return bearer(principalSequence(id));
    }

    private int principalSequence(UUID id) {
        String principal = jdbc.queryForObject("select customer_principal from loan_application where id = ?", String.class, id);
        return Integer.parseInt(principal.substring("applicant-".length()));
    }

    private static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }
}
