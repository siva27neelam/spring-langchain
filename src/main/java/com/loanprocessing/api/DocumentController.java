package com.loanprocessing.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.document.DocumentIntakeService;
import com.loanprocessing.intake.IdempotencyService;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.support.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{id}/documents")
public class DocumentController {
    private final DocumentIntakeService documents;
    private final JdbcGateway gateway;

    public DocumentController(DocumentIntakeService documents, JdbcGateway gateway) {
        this.documents = documents;
        this.gateway = gateway;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> upload(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam(defaultValue = "PAYSLIP") String kind,
            @RequestParam("file") MultipartFile file
    ) throws java.io.IOException {
        IdempotencyService.Completed completed = documents.upload(id, authentication.getName(), LoanApplicationController.operator(authentication), key, kind, file.getBytes());
        return ResponseEntity.status(completed.status()).body((ObjectNode) completed.body());
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication, @PathVariable UUID id) {
        requireOwner(authentication, id);
        List<Map<String, Object>> rows = new ArrayList<>();
        gateway.jdbc().query("select id::text, media_type, byte_size, status from document where application_id = ? order by created_at", rs -> {
            rows.add(Map.of("id", rs.getString(1), "mediaType", String.valueOf(rs.getString(2)), "byteSize", rs.getLong(3), "status", rs.getString(4)));
        }, id);
        return rows;
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(Authentication authentication, @PathVariable UUID id, @PathVariable UUID documentId) {
        byte[] bytes = documents.content(id, documentId, authentication.getName(), LoanApplicationController.operator(authentication));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE).body(bytes);
    }

    private void requireOwner(Authentication authentication, UUID id) {
        var row = gateway.find(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "application not found"));
        if (!LoanApplicationController.operator(authentication) && !row.customerPrincipal().equals(authentication.getName())) {
            throw new ApiException(404, "NOT_FOUND", "application not found");
        }
    }
}
