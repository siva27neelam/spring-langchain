package com.loanprocessing.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.intake.IdempotencyService;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.ApplicationRow;
import com.loanprocessing.security.RateLimiter;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import com.loanprocessing.support.Hashes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentIntakeService {
    private static final int ABSOLUTE_MAX = 20 * 1024 * 1024;
    private final RuntimeConfigurationService config;
    private final IdempotencyService idempotency;
    private final ApplicationLifecycle lifecycle;
    private final JdbcGateway gateway;
    private final DocumentStoragePort storage;
    private final MalwareScanPort scanner;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;

    public DocumentIntakeService(
            RuntimeConfigurationService config,
            IdempotencyService idempotency,
            ApplicationLifecycle lifecycle,
            JdbcGateway gateway,
            DocumentStoragePort storage,
            MalwareScanPort scanner,
            RateLimiter rateLimiter,
            ObjectMapper mapper
    ) {
        this.config = config;
        this.idempotency = idempotency;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.storage = storage;
        this.scanner = scanner;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
    }

    public IdempotencyService.Completed upload(UUID applicationId, String principal, boolean operator, String idempotencyKey, String kind, byte[] bytes) {
        ConfigSnapshot snapshot = config.current();
        rateLimiter.acquire(principal, "upload", snapshot.uploadPerMinute());
        if (kind != null && !"PAYSLIP".equals(kind)) {
            throw new ApiException(422, "VALIDATION", "kind is invalid");
        }
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA", "file is empty");
        }
        if (bytes.length > ABSOLUTE_MAX || bytes.length > snapshot.maxBytes()) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "file exceeds the size limit");
        }
        String media = sniff(bytes);
        if (media == null) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA", "file type is not supported");
        }
        if (media.startsWith("image/") && !snapshot.ocrEnabled()) {
            throw new ApiException(422, "OCR_NOT_CONFIGURED", "image payslips are not enabled");
        }
        if (!snapshot.rawJson().get("documents.payslip.allowed-media-types").contains(media)) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA", "file type is not supported");
        }
        Integer pages = pageCount(bytes, media);
        if (pages != null && pages > snapshot.maxPages()) {
            throw new ApiException(422, "PAGE_LIMIT", "file exceeds the page limit");
        }
        String hash = Hashes.sha256Hex(concat(applicationId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes));
        return idempotency.execute(principal, "POST /applications/" + applicationId + "/documents", idempotencyKey, hash, snapshot,
                () -> store(applicationId, principal, operator, bytes, media, pages, snapshot));
    }

    private IdempotencyService.Completed store(UUID applicationId, String principal, boolean operator, byte[] bytes, String media, Integer pages, ConfigSnapshot snapshot) {
        ApplicationRow row = gateway.lock(applicationId);
        if (!operator && !row.customerPrincipal().equals(principal)) {
            throw new ApiException(404, "NOT_FOUND", "application not found");
        }
        if (row.status() != ApplicationStatus.PAYSLIP_REVIEW) {
            throw new ApiException(409, "STATUS_NOT_ACCEPTING_DOCUMENTS", "application is not accepting documents");
        }
        if (bytes.length > snapshot.maxBytes()) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "file exceeds the size limit");
        }
        String sha = Hashes.sha256Hex(bytes);
        var existing = gateway.jdbc().query("""
                select id::text, sha256, media_type, byte_size from document
                where application_id = ? and sha256 = ? and status <> 'REJECTED'
                """, (rs, n) -> rs.getString(1), applicationId, sha);
        if (!existing.isEmpty()) {
            ObjectNode body = mapper.createObjectNode();
            body.put("id", existing.get(0));
            body.put("sha256", sha);
            body.put("mediaType", media);
            body.put("byteSize", bytes.length);
            body.put("applicationStatus", row.status().name());
            body.put("replay", true);
            return new IdempotencyService.Completed(200, body);
        }
        MalwareScanPort.Verdict verdict = scanner.scan(bytes, media);
        UUID documentId = UUID.randomUUID();
        if (verdict == MalwareScanPort.Verdict.INFECTED) {
            int rejected = count(applicationId, "REJECTED");
            if (rejected >= snapshot.maxRejected()) {
                throw new ApiException(429, "REJECTED_LIMIT", "too many rejected uploads");
            }
            gateway.jdbc().update("""
                    insert into document (id, application_id, kind, status, reject_reason, media_type, byte_size, sha256, page_count, malware_scan, uploaded_by, created_at)
                    values (?, ?, 'PAYSLIP', 'REJECTED', 'MALWARE', ?, ?, ?, ?, 'INFECTED', ?, now())
                    """, documentId, applicationId, media, bytes.length, sha, pages, principal);
            gateway.audit("DOCUMENT_REJECTED", "document", documentId.toString(), "SUCCESS", "MALWARE", mapper.createObjectNode().put("sha256", sha));
            ObjectNode rejectedBody = mapper.createObjectNode();
            rejectedBody.put("id", documentId.toString());
            rejectedBody.put("status", "REJECTED");
            rejectedBody.put("reasonCode", "MALWARE");
            rejectedBody.put("applicationStatus", row.status().name());
            return new IdempotencyService.Completed(422, rejectedBody);
        }
        int accepted = count(applicationId, "RECEIVED");
        if (accepted >= snapshot.maxPayslips()) {
            throw new ApiException(409, "DOCUMENT_LIMIT", "payslip limit reached");
        }
        String key = "applications/" + applicationId + "/documents/" + documentId;
        gateway.jdbc().update("insert into pending_object (object_key, application_id, created_at) values (?, ?, now())", key, applicationId);
        storage.put(key, bytes, media);
        gateway.jdbc().update("""
                insert into document (id, application_id, kind, status, bucket, object_key, media_type, byte_size, sha256, page_count, malware_scan, uploaded_by, created_at)
                values (?, ?, 'PAYSLIP', 'RECEIVED', 'local', ?, ?, ?, ?, ?, 'CLEAN', ?, now())
                """, documentId, applicationId, key, media, bytes.length, sha, pages, principal);
        gateway.jdbc().update("delete from pending_object where object_key = ?", key);
        gateway.audit("DOCUMENT_STORED", "document", documentId.toString(), "SUCCESS", null,
                mapper.createObjectNode().put("sha256", sha).put("byteSize", bytes.length).put("mediaType", media));
        ApplicationStatus status = lifecycle.acceptPayslip(applicationId, row.version(), accepted + 1, snapshot.requiredPayslips());
        ObjectNode body = mapper.createObjectNode();
        body.put("id", documentId.toString());
        body.put("sha256", sha);
        body.put("mediaType", media);
        body.put("byteSize", bytes.length);
        body.put("applicationStatus", status.name());
        return new IdempotencyService.Completed(201, body);
    }

    @Transactional
    public byte[] content(UUID applicationId, UUID documentId, String principal, boolean operator) {
        ApplicationRow row = gateway.find(applicationId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "application not found"));
        if (!operator && !row.customerPrincipal().equals(principal)) {
            throw new ApiException(404, "NOT_FOUND", "application not found");
        }
        var docs = gateway.jdbc().query("""
                select status, object_key from document where id = ? and application_id = ?
                """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, documentId, applicationId);
        if (docs.isEmpty() || "REJECTED".equals(docs.get(0)[0]) || docs.get(0)[1] == null) {
            throw new ApiException(404, "NOT_FOUND", "document not found");
        }
        gateway.audit("DOCUMENT_ACCESSED", "document", documentId.toString(), "SUCCESS", null, mapper.createObjectNode());
        return storage.get(docs.get(0)[1]);
    }

    private int count(UUID applicationId, String status) {
        Integer count = gateway.jdbc().queryForObject(
                "select count(*) from document where application_id = ? and status = ?", Integer.class, applicationId, status);
        return count == null ? 0 : count;
    }

    private static String sniff(byte[] bytes) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "application/pdf";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    private static Integer pageCount(byte[] bytes, String media) {
        if (!"application/pdf".equals(media)) {
            return null;
        }
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    public String mediaOf(byte[] bytes) {
        return sniff(bytes);
    }
}
