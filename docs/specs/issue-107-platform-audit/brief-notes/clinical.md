# Brief Notes — Clinical Trial Coordination

## Capability Area

**Descriptive name:** Clinical Trial Coordination
**Pillar:** Accountability (FDA audit, GCP SLA, PI obligation), Automation (AI-driven eligibility, SUSAR, safety analysis), Coordination (multi-site DSMB rollup, IRB gates)

## What It Does

Agentic harness for clinical trial coordination built on the CaseHub platform. Coordinates eligibility screening agents, safety monitoring agents, PI authorisation gates, and IRB approval gates across multiple trial sites — producing an FDA-compliant, GDPR-aware, independently verifiable audit trail under GCP/ICH E6(R3).

15 JPA entities model the clinical domain (trials, sites, patients, adverse events, deviations, amendments, lab results, vital signs, medications, visits, study drug administration). 22 ledger entry subclasses provide Merkle-chained audit trail coverage for every domain event. 7 YAML case definitions drive adaptive orchestration (AE escalation, deviation review, SUSAR oversight, regulatory submission, eligibility screening, protocol amendment, trial coordination). 5 LLM agents (eligibility screening, SUSAR criteria evaluation, safety signal analysis, trial supervision, protocol amendment) displace rule-based defaults via CDI priority. 16 @McpDomain SPI interfaces generate tri-channel APIs (REST + GraphQL + MCP). IND expedited safety reporting per 21 CFR Part 312.

376 main Java files + 191 test files. Lit-based TypeScript web UI with 4 views and 8 components.

## Differentiation

- **Structural compliance, not bolted-on:** GCP SLA enforcement (24h Grade 3/4, 1h Grade 5) is embedded in the grade enum — `CtcaeGrade.sla()` anchors every SLA computation. Not a configurable timer bolted onto a workflow — the compliance specification IS the code.
- **Formal PI accountability:** Protocol deviations carry a named PI obligation via qhorus COMMAND with policy-configured deadline. The PI's response is a structured JSON mapped through `ClinicalInboundNormaliser`. Not a notification — a commitment lifecycle with audit trail.
- **Tamper-evident FDA audit:** 22 ledger entry types cover every domain event in the Merkle chain. Independent verification via `GET /ledger/verify` (Merkle root) and `GET /audit/entries/{id}/proof` (inclusion proof). W3C PROV-DM export for provenance.
- **Adaptive safety routing via YAML case plans:** Grade-based routing, IRB gate suspension, DSMB rollup — all declared in YAML case definitions, not hardcoded in service code. Trial-level blackboard aggregation detects cross-site Grade 4+ patterns without any site agent having global visibility.
- **Six CBR domains with trajectory monitoring:** AE, deviation, amendment, AE trajectory (DTW-based), site enrollment, trial safety. Learned escalation plans from precedent matching. Trajectory alerting for AE progression deviation.
- **IND deadline enforcement:** Exact absolute FDA deadlines (Grade 3: 15-day, Grade 4/5: 7-day) with two-tier breach escalation policy. Not relative timers — `expiresAtExpression` evaluates from the WORKING panel.
- **Peer-reviewed baseline comparison:** ClinicalAgent (arXiv 2404.14777, ACM BCB '24) cannot provide SLA enforcement, formal PI obligation, consent withdrawal, multi-site rollup, FDA tamper-evident audit, trust-weighted routing, adaptive protocol paths, IND deadline enforcement, or CBR precedent retrieval. 10-row GCP/FDA gap table documented.

## Extent

2 Maven modules (api + runtime). 376 main + 191 test Java source files. 15 JPA entities, 22 ledger entry subclasses, 36 SPI interfaces (16 @McpDomain API + 11 domain policy + 5 LLM agent + 4 notification SPIs), 7 YAML case definitions, 3 case definition classes, 6 CBR domains, 4 memory domains, 8 capability tags, 3 trust dimensions, 5 LLM agents, 4 RBAC roles, 16 @McpDomain tri-channel API surfaces. IND safety report generation. Real-time event cascade via WebSocket/SSE (push endpoint, ConnectionRegistry, SessionSender). Decision narratives (ClinicalNarrativeSignalStrategy). Lit-based web UI: 4 views (work queue, safety workbench, protocol workbench, operations), 8 components. Scenario infrastructure (clinical-trial-demo.yaml). Demo seeder with 3-site oncology trial.

## Consistency Benefit

- **CDI throughout:** All services, listeners, ledger writers, agents, and policies are CDI beans — same lifecycle, injection model, and `@DefaultBean` displacement as every other CaseHub module
- **Shared platform SPIs:** Uses CaseHub (engine), WorkItemCreateRequest (work), MessageService (qhorus), LedgerEntryRepository/LedgerErasureService (ledger), AgentProvider (platform), ConnectorRegistry (connectors), CbrCaseMemoryStore (neocortex), CaseMemoryStore (neocortex), CurrentPrincipal (platform), RoutingFeatureExtractor (blocks), CaseOutcomeObserver (engine), ActionRiskClassifier (engine), SlaBreachPolicy (work), TrustRoutingPolicyProvider (engine-ledger)
- **Tenant-scoped:** `tenant_id NOT NULL DEFAULT 'default'` on all 15 entities. `TenantEntityLookup` utility provides tenant-scoped queries via named JPA queries.
- **Audit trail:** 22 ledger entry types in the Merkle chain. `ComplianceSupplement` for EU AI Act Art.12 with `InvocationMetrics` on every LLM invocation.
- **Trust model:** 3 trust dimensions (safety-accuracy, eligibility-precision, protocol-adherence). `SusarAgentAttestationWriter` writes attestations anchored to `WorkerDecisionEntry`. Bayesian Beta trust scoring via `TrustScoreJob`.
- **@McpDomain tri-channel:** All 16 API surfaces generate REST + GraphQL + MCP from a single SPI interface definition — consistent with platform-wide @McpDomain migration.

## Vision (from open issues)

No open issues as of 2026-10-03. All 173 issues closed.

Future directions from closed issues and specs:
- **Query isolation (#71):** Multi-tenant query isolation beyond entity-level `tenantId` stamping
- **Full trust routing (#164 epic, closed):** Agent org structure, model tier selection, decision narratives — all implemented
- **CBR Phase 7 (#120, closed):** Multi-scope memory for DSMB pattern detection — implemented

## E2E Use Case Fragment

**Multi-site oncology trial safety monitoring:** Trial activated across 3 sites → patients enrolled → adverse event reported at Site A (Grade 3) → `AdverseEventService` creates WorkItem with 24h SLA, writes `AdverseEventLedgerEntry` to Merkle chain → `ae-escalation.yaml` routes to senior monitor → LLM SUSAR criteria evaluator assesses causality per ICH E2A → trust-weighted routing selects highest-trust safety agent → simultaneous Grade 4+ AE at Site B → trial-level blackboard binding fires DSMB rollup → `TrialSafetyAggregationJob` detects cross-site cluster → `LlmSafetySignalAnalyzer` enriches with DSMB narrative → `DsmbSafetySignalEvent` fires → CBR stores the full plan trace as precedent → next similar AE retrieves learned escalation plan → Protocol deviation at Site C → COMMAND to named PI with 24h deadline → PI approves → CRITICAL severity triggers IRB gate → 72h WorkItem, case suspends → IRB decides APPROVED → deviation resolved → full lifecycle Merkle-verifiable by FDA auditor.

This exercises all five lifecycle stages: Declaration (YAML case definitions) → Intelligence (LLM agents, CBR learning) → Control (engine orchestration, trust routing) → Convergence (multi-site DSMB rollup, trajectory alerts) → Operations (SLA monitoring, IND deadline enforcement). The feedback loop: CBR stores every outcome, enriching future routing and escalation decisions.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 7 YAML case definitions; adaptive routing via binding conditions; trial-level blackboard aggregation |
| Human Task Management | WorkItems with grade-keyed SLA (24h/1h/7d); IRB 72h gate; IND deadline enforcement with breach escalation |
| Agentic Communications | PI COMMAND/Commitment lifecycle; per-deviation qhorus channels; structured JSON normalisation |
| Trust & Accountability | Merkle chain (22 entry types); Bayesian trust (3 dimensions); attestation-anchored scoring; EU AI Act Art.12 compliance supplements |
| Case-Based Reasoning | 6 CBR domains; learned escalation plans; trajectory monitoring (DTW); compaction; retention; cross-trial memory |
| Agent Memory | 4 structured memory domains (patient, site, drug, IRB) via neocortex-memory |
| Agent Infrastructure | 5 LLM agents via AgentProvider SPI; model tier selection; ClinicalAgentSupport shared utility |
| Trust-Weighted Routing | Per-capability trust policies; safety-monitoring 0.75 threshold with 0.70 quality floor |
| Oversight Gates | 5 ALWAYS-gated action types via ClinicalActionRiskClassifier |
| Notification Pipeline | Safety officer notification (Grade 3+); durable sponsor notification with async retry |
| GDPR & Privacy | Patient consent withdrawal; ledger erasure; memory erasure; decision context sanitisation |
| Regulatory Reporting | IND expedited safety report generation (21 CFR Part 312) |
| @McpDomain Tri-Channel | 16 API surfaces generating REST + GraphQL + MCP from single SPI definitions |
| Real-Time Push | WebSocket SSE cascade timeline for orchestration visibility |
| Decision Narratives | ClinicalNarrativeSignalStrategy for human-readable decision traces |
| Simulation & Demo | Scenario infrastructure; 3-site oncology demo seeder |
| Web UI | casehub-pages + blocks-ui: work queue, safety workbench, protocol workbench, operations dashboard |