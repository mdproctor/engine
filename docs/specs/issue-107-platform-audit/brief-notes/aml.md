# Brief Notes — Anti-Money Laundering Investigation

## Capability Area

**Descriptive name:** Anti-Money Laundering Investigation
**Pillar:** Accountability (tamper-evident SAR audit trail, FinCEN compliance), Automation (adaptive investigation paths, trust-weighted agent routing), Collaboration (compliance officer oversight gates, human-in-the-loop SAR approval)

## What It Does

Coordinates specialist AI agents (entity resolution, pattern analysis, OSINT screening, SAR drafting) through adaptive investigation paths driven by YAML case definitions. Investigation triage uses rule-based scoring with CBR adjustment from similar past cases. Trust-weighted routing selects agents by capability track record (Bayesian Beta from SAR outcome attestations). Produces a FinCEN-compliant, independently verifiable audit trail with Merkle inclusion proofs per decision point. Compliance officer review gates enforce 30-day FinCEN SLA with automatic escalation. GDPR Art.17 erasure with tamper-evident receipts. W3C PROV-DM provenance export for investigation lineage.

## Differentiation

- **Accountability is structural, not logged:** Every specialist finding traces to the commitment that produced it via `causedByEntryId` chain. Merkle inclusion proofs are independently verifiable. This is not an append-only log — it is a cryptographic proof that the record has not been tampered with. No AML platform offers this.
- **Investigation paths adapt to evidence:** The engine's binding evaluation fires specialist workers based on evidence state — PEP entities automatically route to senior analysts, CBR path advice influences triage, gate rejections trigger re-investigation with escalation paths. 13 capabilities, 20 bindings, 4 goals — all declared in YAML, not coded in Java.
- **Trust-weighted routing from outcomes:** Agent selection uses Bayesian Beta distributions seeded with domain-appropriate priors. SAR verdict feedback closes the loop — investigation accuracy, PEP clearance, and scope awareness are distinct trust dimensions. Workers below capability threshold are excluded from selection.
- **CBR-driven investigation intelligence:** Past case outcomes feed triage decisions, path recommendations, and SAR narrative seeding. Activation threshold (default 30 cases) gates CBR influence to prevent early noise. Sanitised narratives from similar past SARs seed the drafting worker, with PII protection via `ContentSanitiser`.
- **Oversight gates with rejection routing:** `PlannedAction(SAR_FILING)` creates a formal MLRO gate. If rejected, a 6-step rejection routing path fires: senior analyst review → re-triage → escalation to head of compliance → re-drafting or stall. The rejection path is itself YAML-declared, not hardcoded.
- **9-layer tutorial structure:** Each layer adds one foundation module and makes its value tangible. All code is production-grade. The comparison showcase demonstrates the accountability gap each layer closes — a teaching tool for enterprise Java developers in financial services.

## Extent

2 Maven modules (api + app). 332 Java source files (214 main + 118 test). 2 YAML case definitions (main investigation: 13 capabilities, 20 bindings, 4 goals; oversight: 3 capabilities, 3 bindings, 1 goal). 13 workers in main investigation, 3 in oversight. 3 @McpDomain APIs (investigations, audit, compliance) generating REST + GraphQL + MCP surfaces. 6 YAML scenarios (pep-investigation, structuring-ring, clean-transaction, gate-rejection, gdpr-erasure, trust-evolution). 3 trust dimensions. 5 oversight action types with gate policies. 6 domain-specific ledger entry types. Lit-based web UI with investigation workbench (split-pane case list + detail tabs), compliance officer work queue, and operations dashboard. CBR case base with synthetic seeding for cold-start bootstrap.

## Consistency Benefit

- **CDI throughout:** All 13 workers, trust routing, CBR integration, compliance evidence, memory service, ledger service — CDI beans with the same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses YamlCaseHub (engine), FlowWorkerFunction (engine-flow), TrustRoutingPolicyProvider (engine-ledger), ActionRiskClassifier (engine-work-adapter), WorkItemService (work), MessageService (qhorus), LedgerWriteService (ledger), CaseMemoryStore (neocortex-memory), CbrCaseMemoryStore (neocortex-cbr), PreferenceProvider (platform-config), ContentSanitiser (ledger)
- **Tenant-scoped:** Same tenancy model — `McpIdentityContext` for MCP tenancy, `CurrentPrincipal` for identity
- **Audit trail:** Domain ledger entries (6 types) integrate with platform Merkle chain via JOINED inheritance from `LedgerEntry`. Trust routing attestations capture score immutably at routing time.
- **YAML case definitions:** Both investigation types use the standard CaseHub YAML DSL — `capabilities`, `bindings`, `goals`, `completion` — parsed by `YamlCaseHub` at startup
- **@McpDomain tri-channel:** Single interface generates REST, GraphQL, and MCP tool surfaces

## Vision (from closed issues — no open issues)

All 130 issues are closed. The application is feature-complete as a reference architecture and tutorial. Potential extensions:
- **Account-scoped → entity-scoped memory:** Cross-account intelligence for beneficial owners across multiple accounts (currently deferred — each account builds independent context)
- **Cascade GDPR erasure for network cross-references:** Account A's erasure removes A's memories but B's memory still names A (requires legal assessment)
- **FlowWorkerFunction PlannedAction support:** 3 workers remain on `WorkerFunction.Sync` pending engine#564

## E2E Use Case Fragment

**AML investigation lifecycle:** Transaction flagged by monitoring → investigation case opens → CBR retrieves similar past cases → entity resolution identifies beneficial ownership chain → PEP detection routes to senior analyst automatically → pattern analysis and OSINT screening fire in parallel → investigation triage evaluates findings with CBR adjustment → SAR warranted → narrative drafted with seeded text from similar past SARs → `PlannedAction(SAR_FILING)` triggers MLRO oversight gate → compliance officer claims work item (30-day FinCEN SLA) → SAR approved → compliance review WorkItem opened → SAR outcome verdict (UPHELD/WITHDRAWN/FLAGGED) feeds back to trust model → future routing improves.

If gate rejects: senior analyst reviews → re-triage → escalation to head of compliance → re-draft or stall. Every step is tamper-evident with Merkle proofs. GDPR erasure available at any point with tamper-evident receipts.

This is the full Declaration → Intelligence → Control → Convergence → Operations loop applied to financial crime investigation. The 9-layer tutorial structure makes each platform capability's value concrete and measurable.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 2 YAML case definitions (investigation + oversight), 16 workers total |
| Trust & Accountability | 3 trust dimensions, Bayesian Beta routing, SAR outcome attestations, Merkle audit chain |
| Human Task Management | Compliance officer 30-day FinCEN SLA, MLRO oversight gates, escalation |
| Oversight Gates | 5 action types (SAR_FILING, ACCOUNT_RESTRICTION, TRANSACTION_BLOCKING, ENTITY_LINK_CREATION, LAW_ENFORCEMENT_REFERRAL) |
| Case-Based Reasoning | Triage adjustment, path advice, SAR narrative seeding, synthetic case base bootstrapping |
| Agent Memory | Prior entity context across investigations, SAR outcome memories, 3 memory domains |
| Agentic Communications | Specialist COMMAND/RESPONSE/DECLINE per agent via qhorus |
| GDPR Erasure | Actor-level + entity-level erasure, cross-tenant support, retention expiry job, tamper-evident receipts |
| Compliance Evidence | 4 requirement-scoped evidence records (audit chain, SLA, trust routing, GDPR), Merkle inclusion proofs |
| W3C Provenance | PROV-DM JSON export of investigation lineage |
| Simulation & Scenarios | 6 YAML scenarios, temporal simulation, CBR case base seeding |
| @McpDomain Tri-Channel | 3 domain APIs generating REST + GraphQL + MCP tool surfaces |
| Notification Pipeline | Compliance escalation routing |
| Shared Platform Services | PreferenceProvider for tunable thresholds, identity, tenancy, expressions |