# Brief Notes: Trust & Accountability Ledger

## Capability Area

**Pillar:** Collaboration → Trust & Accountability
**Lifecycle stage:** Control (tamper-evident execution with full accountability)

## Key Capabilities

- **Tamper-Evident Audit Trail** — immutable append-only ledger with Merkle Mountain Range (RFC 9162). O(log N) inclusion proofs. Ed25519 signed checkpoints. Every agent decision, human action, and system event is cryptographically committed.

- **Trust Scoring** — Bayesian Beta reputation with exponential decay. Four score types: GLOBAL, CAPABILITY, DIMENSION, QUALITY. EigenTrust power iteration for transitive trust across the agent mesh. Materialized snapshots for low-latency routing. Incremental recomputation on each attestation.

- **Peer Attestation** — agents and humans can attest to the quality of any ledger entry (SOUND / FLAGGED / ENDORSED / CHALLENGED). Confidence scores. Capability-scoped attestation. Aggregation strategies (weighted majority, unanimous, first attestor).

- **Agent Identity Verification** — DID-based identity binding, credential validation, public key caching. Identity verification entries are themselves part of the Merkle chain.

- **GDPR / Privacy** — actor pseudonymisation (token-based Art.17 erasure with tamper-evident receipts). Decision context sanitisation SPI. Erasure severs the token-to-identity mapping; ledger entries remain permanently anonymous.

- **Compliance Reporting** — EU AI Act Article 12 compliance via ComplianceSupplement. Qute HTML templates, PDF generation, JSON/CSV export. Audit trail export with Merkle verification.

- **Cloud KMS Signing** — four cloud providers (AWS KMS, GCP Cloud KMS, Azure Key Vault, HashiCorp Vault Transit). Each has a framework-neutral Java core + Quarkus CDI adapter + Spring auto-configuration. 403-retry protocol for token refresh.

- **Annotation-Driven Audit** — @Audited, @Attested, @ComplianceSupplement annotations with build-time validation. Zero-code audit trail for any CDI/Spring method.

- **Federation** — trust score export/import across deployments. Bootstrap from external trust sources.

## Differentiation

- **Accountability is structural, not bolted on.** Most audit systems are separate logging layers. CaseHub's ledger is cryptographically tamper-evident (Merkle MMR) and integrated into the platform's CDI event model — every agent decision, case transition, and human task completion writes to the same append-only chain with a single consistent API.

- **Trust scoring is bayesian, not rule-based.** Competitors use simple threshold rules. CaseHub computes trust from attestation history using Bayesian Beta distributions with exponential decay and asymmetric valence (negative signals persist longer). EigenTrust adds transitive trust across the agent mesh — an agent trusted by trusted agents inherits credibility.

- **Framework-neutral core.** After the #213 extraction, all service logic lives in constructor-injected POJOs (ledger-core). Both Quarkus and Spring Boot are thin wrappers. This is unusual for a CDI-first project and demonstrates the platform's portability commitment.

- **Privacy by design.** GDPR Art.17 erasure is a first-class operation, not an afterthought. Pseudonymisation is automatic for human actors. Erasure receipts are themselves tamper-evident. Decision context sanitisation is pluggable.

## Extent

- 12 active modules + 8 signing sub-modules + 6 example modules
- 11 SPI interfaces in the API module
- 28+ service classes in runtime
- 6 identity enrichers/services
- 3 federation services (export, import, bootstrap)
- 4 cloud KMS providers × 3 flavors (core, Quarkus, Spring)
- 14 JPA entities
- 56-heading consumer guide, 50-heading contributor guide
- 17 ADRs, 60+ design specs

## Consistency Benefit

- Uses the platform CDI model — @DefaultBean no-ops, @Alternative activation, @Priority ordering
- Tenant-scoped repositories match the platform tenancy pattern
- Event model (CDI events for trust routing, attestation recorded) integrates with the platform CloudEvent dispatcher
- Enricher pipeline pattern reused across the platform
- Agent identity integration via platform ActorDIDProvider and AgentCredentialValidator

## Vision (from open issues)

- **Spring completeness** (#216 epic) — 53 CDI beans still not in ledger-core, JPA generation candidate
- **Unified API generation** (#207) — @McpDomain SPI with JAX-RS annotations
- **Full framework parity** — ledger should deploy identically on Quarkus and Spring Boot

## Cross-Platform Patterns

- Trust routing events feed into engine's routing pipeline for trust-weighted worker selection
- Qhorus (Agentic Communications Mesh) extends LedgerEntry for message audit trail
- Work (Human Task Management) extends LedgerEntry for work item event audit
- Engine (Case Orchestration) optionally extends LedgerEntry for case events
- Every platform subsystem that needs accountability uses the same LedgerAppender SPI