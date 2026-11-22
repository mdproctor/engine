# Brief Notes — Personal Life Automation

## Capability Area

**Descriptive name:** Personal Life Automation
**Pillar:** Automation (household task orchestration + sentinel monitoring), Coordination (commitment lifecycle + oversight gates), Collaboration (family delegation + external actor management)

## What It Does

Coordinates household management, health appointments, financial governance, travel planning, contractor follow-up, elder care, legal compliance, and family scheduling — producing formally tracked, SLA-enforced, tamper-evident records of life obligations and decisions. 8 life domains, each with a typed descriptor carrying capability tags, routing policies, SLA policies, and worker bindings. 8 YAML case definitions orchestrate multi-step workflows with adaptive gates, cross-case signals, M-of-N family quorum votes, and sentinel heartbeat monitoring. 4 AI agent personas (health, home, finance, travel) execute via OpenClaw direct-call integration with trust-weighted routing across 4 dimensions (deadline-reliability, cost-accuracy, factual-accuracy, proactive-alerting). 6 CBR feature schemas learn from case outcomes — routing and plan adaptation improve with each resolution. 6 @McpDomain APIs expose tri-channel access (REST + GraphQL + MCP).

## Differentiation

- **Accountability on personal life:** No consumer product applies formal SLA enforcement, tamper-evident audit, and commitment tracking to household obligations. Smart home apps do device control; task apps do to-do lists. Neither enforces deadlines with escalation, tracks verbal contractor commitments with Watchdog follow-up, or produces Merkle-chained audit records of health decisions.
- **Oversight gates for consequential actions:** 12-type action risk taxonomy with RBAC-differentiated thresholds. Financial decisions above a threshold cannot proceed without household-admin approval — the WorkItem doesn't exist until the approval arrives. No consumer home automation product has agent-action gating with configurable risk policies.
- **CBR-driven household learning:** 6 domain feature schemas with dual-path outcome recording (per-worker routing outcomes + case-level outcomes). Plan adaptation from similar past cases. Trust feature enrichment. The household gets better at managing itself over time — contractor routing improves, travel planning adapts from past trips, health appointment scheduling learns from outcomes.
- **7 sentinel heartbeat monitors:** Persistent monitoring for long-running cases: contractor progress, IoT sensor anomalies, health follow-ups, care quality, patient status, financial anomalies, booking status. Each sentinel is a typed LLM agent with a response schema, operating on cross-channel context from delegation, oversight, and per-actor channels. Not a cron job — a context-aware, LLM-powered watchdog.
- **Tutorial showcase:** 8-layer progressive adoption sequence demonstrating CaseHub platform integration. Each layer adds one foundation module and closes specific accountability gaps. Most comprehensive platform integration example — exercises engine, work, qhorus, ledger, trust, and OpenClaw simultaneously.

## Extent

2 Maven modules (api + app). 207 main Java source files, 120 test files. 8 YAML case definitions. 6 @McpDomain APIs (cases, tasks, actors, dashboard, analytics, events). 4 OpenClaw agent personas. 7 sentinel heartbeat types. 6 CBR feature schemas with 6 adaptation rules and 6 description providers. 4 LedgerEntry subclasses. 3 commitment strategies. 12 household action types. 3 permission groups. 4 trust dimensions. 8 domain descriptors. Lit 3.x frontend with 5 views (home, inbox, cases, journal, people) and 12+ panels. SSE event streaming for real-time UI updates.

## Consistency Benefit

- **CDI throughout:** case services, commitment strategies, sentinel jobs, CBR registrars, ledger observers, risk classifiers — all CDI beans with standard lifecycle
- **Shared platform SPIs:** LifeSlaBreachPolicy (work SPI), LifeCommitmentStrategy (qhorus commitment patterns), LifeRoutingOutcomeRecorder (engine SPI), LifeActionRiskClassifier (engine SPI), LifeTrustRoutingPolicyProvider (engine SPI), LifeCaseOutcomeCbrWriter (engine SPI)
- **@McpDomain APIs:** same tri-channel generation pattern as all other CaseHub applications — REST + GraphQL + MCP from one set of annotated methods
- **YamlCaseHub extension:** LifeTypedCaseHub template method pattern — same case definition approach as all engine-backed applications
- **Tenant-scoped:** hierarchical WorkItem scope format `casehubio/life/{domain}` — same scoping convention as all CaseHub apps
- **Audit trail:** 4 domain-specific LedgerEntry subclasses following the JOINED inheritance pattern established by ledger
- **Trust integration:** same Bayesian Beta trust scoring from casehub-ledger, same attestation pipeline, same routing policy provider SPI

## Vision (from open issues)

- **Personal AI companion (#117, XL):** Check-in conversations, calendar integration, proactive daily briefing. Conversational interface wrapping all household capabilities.
- **Open Banking (#115, XL):** Transaction feeds, bill tracking, spend categorisation. Real financial data feeding the financial review case type.
- **Communication intake (#112 email, #113 WhatsApp, L/High each):** Inbound message parsing, actor recognition, commitment extraction. Natural language → structured commitments.
- **Calendar sync (#114, M/Med):** Bidirectional Google Calendar integration. Appointments from case orchestration reflected in family calendar.
- **Notification delivery (#111, M/Med):** SLA breaches and alerts to real channels (WhatsApp, email, push).
- **Conversational intake (#110, L/High):** Natural language to API calls. "Book a plumber for Thursday" → case creation + commitment.
- **Family mind map (#121, L/High):** Neocortex semantic knowledge graph per household. Shared family knowledge base.
- **Voice intake (#120, M/High):** Avatar voice detection for household member identification.

## E2E Use Case Fragment

**Contractor home maintenance with oversight:** Household member creates maintenance case → engine starts home-maintenance workflow → AI home-agent schedules inspection (trust-weighted from past contractor performance) → CBR retrieves similar past repairs for plan adaptation → contractor quoted via qhorus COMMAND on per-actor channel → Watchdog monitors for response → quote arrives as RESPONSE → amount exceeds threshold → oversight gate fires on life/oversight channel → household-admin reviews and approves → WorkItem created from deferred commitment → contractor monitored by maintenance-sentinel (reads IoT sensors, 4h intervals) → job completes → payment gate with RBAC threshold check → completion recorded → case outcome writes to CBR and trust attestations → next similar repair routes to the same contractor (or not, if trust score degraded).

This is the full Declaration → Intelligence → Control → Convergence → Operations loop applied to personal life. No consumer product covers this end-to-end for household management.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 8 YAML case definitions with adaptive gates, cross-case signals, M-of-N quorum |
| Work Item Management | SLA-enforced household tasks with domain supplement pattern |
| Commitment Lifecycle | 3 strategies: family delegation, contractor COMMAND/RESPONSE, oversight gates |
| Merkle Audit | 4 domain LedgerEntry subclasses for health, financial, legal, GDPR erasure |
| Trust & Routing | 4 trust dimensions, 8 domain routing policies, Bayesian Beta from attestations |
| Case-Based Reasoning | 6 feature schemas, plan adaptation, dual-path outcome recording |
| Watchdog Monitoring | APPROVAL_PENDING on commitment channels, auto-escalation |
| Agent Execution | 4 OpenClaw agent personas, direct-call integration |
| Sentinel Heartbeats | 7 persistent monitors with typed LLM agents |
| Risk Classification | 12 action types, RBAC-differentiated thresholds, fail-secure |
| SSE Events | Real-time inbox/case/stream events for frontend |
| GDPR Erasure | PII nullification with erasure proof in ledger |
| @McpDomain APIs | 6 domains: cases, tasks, actors, dashboard, analytics, events |
| OIDC Authentication | @RolesAllowed on all REST resources, 3 permission groups |