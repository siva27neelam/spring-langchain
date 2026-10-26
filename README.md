# loan-processing-service

Spring Boot service for loan intake, a stub credit score, payslip extraction, and a deterministic salary decision.

The model extracts fields through LangChain4j. `DecisionService` is the only component that can auto-approve or auto-decline. Monthly salary **greater than 7000** declines. **7000.00** can approve when identity matches and extraction is certain.

## Run

PostgreSQL 16, Java 21, and Maven are required.

```bash
# database loan_processing, role loan_app
mvn spring-boot:run
```

Defaults live in `src/main/resources/application.yml`. Business knobs are seeded by Flyway and changed through the admin config API. Sensitive keys need a second admin (`four-eyes`).

Tests use database `loan_processing_test` and scripted adapters:

```bash
mvn test
```

Non-production auth is `Authorization: Bearer <role>:<principal>` where role is `applicant`, `operator`, `compliance`, or `admin`. Production boot refuses that mode, a scripted scanner, a scripted model, and a disabled malware scan.

## What is stubbed

- Credit: `StubCreditReportAdapter` returns the configured score (default 600). No Experian client.
- Malware scanning: the default adapter fails closed. Tests use a scripted scanner. Nothing marks a file clean without a scan.
- Object storage: local filesystem. No presigned URLs.
- KMS: local AES-GCM envelope encryption.
- Chat model: OpenAI-compatible when `llm.model` is set. Tests use an in-process echo model. The seed model is `unconfigured`, so extraction holds and sends no payslip text.

Operator decisions, OCR, notifications, and retention erasure stay off the automatic path. See `docs/runbook.md`.
