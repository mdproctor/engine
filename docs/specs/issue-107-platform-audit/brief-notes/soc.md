# Brief Notes — Security Operations Center (SOC)

## Capability Area

**Descriptive name:** AI-Augmented Cyber Incident Response
**Pillar:** Automation (alert → case → investigation → containment pipeline), Coordination (multi-agent investigation with qhorus speech acts), Collaboration (analyst review with SLA, trust-weighted routing)
**App type:** Application — consumes platform capabilities, does not extend them

## What It Does

Multi-agent cyber incident response built on the CaseHub platform. SIEM/EDR alerts arrive as CloudEvents, flow through the RAS detection pipeline (SiemAlertGanglion for severity classification, BruteForceDetectorGanglion for auth anomalies), trigger incident investigation cases, and are investigated by dual rule-based and LLM workers — all coordinated by the adaptive case engine. The investigation pipeline has 8 capabilities across 9 context-change bindings: CBR retrieval → IOC enrichment → ATT&CK mapping → RAG retrieval → containment recommendation → analyst review (human task) → containment execution → recovery verification (desired-state reconciliation).

171 main + 101 test = 272 Java source files across 2 Maven modules (api + app). 7 @McpDomain API classes (tri-channel: REST + GraphQL + MCP). 3 RAS situation definitions. 3 containment connectors (CrowdStrike, Palo Alto, Okta/Azure AD). MITRE ATT&CK STIX 2.1 ingestion with MindMap subgraph and RAG corpus.

## Differentiation

- **Full lifecycle proof point:** Alert → Detection → Investigation → Containment → Verification → Resolution → Knowledge Retention. Every stage uses a different platform capability. No competitor offers this end-to-end with one platform.
- **Dual-worker architecture:** Rule-based and LLM workers coexist for every triage capability (IOC enrichment, ATT&CK mapping, containment recommendation). Trust-weighted routing selects based on accumulated attestation evidence. NOT @DefaultBean/@Alternative displacement — both candidates always available.
- **Desired-state recovery verification:** After containment executes, a desired-state graph verifies the containment took effect. SocContainmentNodeSpec (8 sealed variants) represents each containment action as a NodeSpec. SocRecoveryVerificationListener (GlobalReconciliationListener) tracks convergence and signals the case when all nodes verify. SocContainmentFaultPolicyProducer creates divergence review nodes (human-gated) on failure. No other incident response platform models post-containment verification as a reconciliation problem.
- **CBR learning loop:** SocCbrRetainService stores resolved incidents with problem/solution/outcome. SocCbrRetrieveService informs future triage — past incidents are injected into the investigation pipeline as context for all downstream workers. SocKnowledgeIngestor feeds resolved incidents into the RAG corpus for prose retrieval.
- **ATT&CK as first-class infrastructure:** STIX 2.1 parser with full graph model (techniques, groups, tools, malware, mitigations, relationships). Ingested into MindMap subgraph for structural queries and RAG corpus for prose retrieval. Not just a tag taxonomy — a navigable knowledge graph.
- **Containment governance:** SocActionRiskClassifier gates irreversible actions with fail-closed semantics. 9 action types with 4 gate policies (NEVER/ALWAYS/RISK_SCORE_THRESHOLD/CONFIDENCE_THRESHOLD). SocContainmentCommitmentBridge publishes containment decisions as qhorus speech acts (PROPOSE/DONE/DECLINE/STATUS) for normative accountability.

## Extent

2 Maven modules, 272 Java source files. 8 case definition capabilities, 9 bindings, 3 goals. 10+ workers (7 rule-based + 3 LLM). 3 RAS situations. 7 @McpDomain API classes. 3 containment connectors. 7 compliance ledger observer entry points. 8 desired-state NodeSpec variants. 21 typed DTO records. Full test coverage: unit tests for all workers, risk classifier, CBR, compliance; integration tests for alert-to-case, analyst workitems, brute force, containment pipeline, CBR retrieval, connector containment, recovery verification.

## Consistency Benefit

- **CDI throughout:** All SOC domain logic — workers, risk classifier, compliance observers, notification bridge, trust attestation, CBR, RAG, mesh bridge, containment connectors — are CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** CaseInputContributor (engine), CaseOutcomeObserver (engine), SlaBreachPolicy (work), ActionRiskClassifier (engine), AgentDescriptorRegistrar (eidos), CbrCaseTypeRegistration (neocortex), GlobalReconciliationListener (desiredstate), NodeProvisioner (desiredstate), ActualStateAdapter (desiredstate), @McpDomain (platform)
- **Case definition YAML:** Investigation pipeline is declarative — 8 capabilities with JQ input/output projections, sequential enforcement via binding guards, human task with scope/expiry expressions. Workers are plain Java objects registered programmatically.
- **Tenant-scoped:** Same tenancy model as all other repos — CurrentPrincipal, tenant-scoped queries, per-tenant CBR memory, per-tenant RAG corpus
- **Audit trail:** SocLedgerEntry (JpaLedgerEntry JOINED inheritance) with SocStepType enum. Full decision chain: ALERT_TRIAGE → INCIDENT_PROMOTED → CONTAINMENT_GATE_DECISION → CONTAINMENT_APPROVAL/REJECTION → CONTAINMENT_EXECUTED → INCIDENT_RESOLVED. PII sanitisation via SocPiiSanitiser.

## Vision (from open issues)

- **Blocks orchestration patterns (#48):** Debate for threat assessment, parallel enrichment, voting for containment decisions. XL/High — would extract SOC-specific patterns to blocks.
- **Drools CEP integration (engine#809):** Temporal pattern detection for multi-stage attacks — reconnaissance + initial access within 1 hour → escalate. Blocked on engine upstream.
- **Multi-tenant SOC-as-a-Service:** Per-tenant EDR/SIEM configuration, per-tenant SLA policies, PostgreSQL RLS, per-tenant trust scores and agent pools.
- **Ledger module restructuring (#62):** Adapt to upstream JpaLedgerEntry, trust, compliance API breaks.

## E2E Use Case Fragment

**Critical SIEM alert investigation:** SiemAlertGanglion classifies CloudEvent (CRITICAL severity, 0.95 confidence) → RAS situation threshold met → incident-investigation case created → CBR retrieves 3 similar past incidents → rule-based IOC enrichment extracts 5 indicators (2 IPs, 1 domain, 2 file hashes) → ATT&CK mapping identifies T1566.001 (Spearphishing Attachment) with TA0001 (Initial Access) via MindMap graph enrichment → RAG retrieves relevant threat intel prose → containment recommendation: ISOLATE_HOST (risk 0.85, gate policy ALWAYS) → SocActionRiskClassifier gates → WorkItem created for soc-manager with 30-minute SLA → analyst reviews enriched context (IOCs, ATT&CK narrative, CBR precedents, RAG context) and confirms → containment executed via CrowdStrike Falcon API → desired-state reconciliation verifies host is isolated → SocRecoveryVerificationListener signals case on convergence → trust attestation written → incident retained in CBR for future triage → resolution ledger entry seals the tamper-evident chain → DORA response time captured.

This exercises: RAS detection, case orchestration, CBR (retrieve + retain), RAG retrieval, dual-worker routing, risk classification, work items with SLA, containment execution, desired-state reconciliation, trust attestation, compliance ledger, notification pipeline, and qhorus speech acts — 13 platform capabilities in one incident flow.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | incident-investigation case definition: 8 capabilities, 9 bindings, 3 goals |
| Situation Awareness | 2 ganglia (SiemAlertGanglion, BruteForceDetectorGanglion), 3 situation definitions |
| Case-Based Reasoning | CBR retain on resolution, retrieve on new alert, seed data loader, schema registration |
| RAG Retrieval | ATT&CK reference corpus + per-tenant internal knowledge corpus, post-mortem ingestion |
| Human Task Management | Analyst review WorkItem with SLA, scope expressions, escalation chain |
| SLA Enforcement | SocSlaBreachPolicy: P1/P2/P3 escalation chains, tier-based routing |
| Worker Infrastructure | 10+ workers: 7 rule-based + 3 LLM, AgentWorkerFunction, PlannedAction |
| Risk Classification | SocActionRiskClassifier: 9 action types, 4 gate policies, fail-closed |
| Trust & Accountability | SocAttestationService writes attestations; Bayesian trust-weighted routing |
| Compliance Ledger | SocLedgerEntry (JOINED), 6 step types, Merkle proofs, DORA reports, PII sanitisation |
| Desired-State Reconciliation | 8 containment NodeSpec variants, fault policies, recovery verification listener |
| Agentic Communications | SocContainmentCommitmentBridge: oversight channel speech acts (PROPOSE/DONE/DECLINE/STATUS) |
| Notification Pipeline | SocNotificationBridge: 8 event types, SocNotificationSeeder: system-scope subscriptions |
| Agent Identity | SocAgentDescriptors: 6 agent descriptors with MITRE ATT&CK epistemic domains |
| @McpDomain API Surface | 7 API classes: incidents, alerts, CBR, compliance, trust, KPIs, demo |
| Shared Platform Services | Identity, tenancy, expressions (JQ), SSE streaming |