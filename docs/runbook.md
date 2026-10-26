# Loan processing runbook

Production traffic still waits on a recorded answer to the salary rule (higher monthly salary declines; 7000.00 can approve). Do not invert that comparison in an incident.

## CREDIT_FAILED

The credit worker exhausted `lifecycle.credit.retry-max-attempts` or the port kept failing. The score was not stored as a success.

1. Read `GET /api/v1/admin/applications/{id}/audit` and the `credit_inquiry` row. Confirm the application is `CREDIT_FAILED`.
2. Fix the credit adapter or the stub snapshot. Do not insert a score by hand.
3. An operator calls `POST /api/v1/admin/applications/{id}/retries` with `{"step":"CREDIT"}`. That moves the application to `CREDIT_IN_PROGRESS` and enqueues `ApplicationSubmitted` again.
4. A retry must not approve. Credit still cannot transition to `APPROVED`.

## EXTRACTION_FAILED

Parsing or the model used the extraction attempt budget. Payslip bytes stay in object storage.

1. Confirm `EXTRACTION_FAILED` and that no `APPROVED` decision exists.
2. If the model id is `unconfigured`, set `llm.model` with four-eyes. Do not point it at a free-form prompt.
3. An operator calls the retry endpoint with `{"step":"EXTRACTION"}`.
4. A lost lease discards the extraction run and does not write an approval. Wait for the lease to expire or retry after `EXTRACTION_FAILED`.

## Poison messages

`outbox_dead_letter` plus status `MANUAL_HOLD` / `PROCESSING_POISON` means the handler failed until the attempt budget, or the payload had no application id.

1. Do not republish a payload that contains document text or an SSN. The payload is only ids.
2. Inspect the audit action `DEAD_LETTERED`.
3. Resolve the hold with the operator manual-decision API after a person has reviewed it. That route works only from `MANUAL_HOLD`.
4. Nothing in this path auto-approves.

## Changing the salary rule

`decision.salary.threshold` and `decision.salary.comparison` are four-eyes keys. The default comparison is `GREATER_THAN` and the default threshold is `7000`. The rule fires as a **decline**. Equal to 7000 does not fire.

1. Propose the change with a reason.
2. A different config admin approves it. The proposer cannot approve.
3. New decisions store the config generation they used. In-flight extractions keep the snapshot pinned on `extraction_run`.
4. Uncertainty cannot be set to `APPROVED`. A clear mismatch cannot be set to `APPROVED`.

## Not automated here

These need a real environment. Do not treat a green unit test as coverage:

- Disk full on the database volume or object-storage root.
- Replica lag and failover.
- Clock skew between application nodes (leases use PostgreSQL `now()`, not the JVM clock).
- Egress denial. This build has no Experian client to block.
- A migration that was not applied. Readiness includes the database and the config prime check; confirm Flyway history before sending traffic.

Retention stays off until counsel sets `retention.enforced`. The job does not erase rows while that flag is false.
