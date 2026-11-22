# Brief Notes — AI-Assisted Software Engineering

## Capability Area

**Descriptive name:** AI-Assisted Software Engineering
**Pillar:** Automation (content-driven review routing, LLM reviewer agents), Coordination (PR lifecycle orchestration, merge queue batching, cross-repo coordinated merge), Collaboration (human review gates with SLA, trust-weighted assignment)

## What It Does

Coordinates AI and human specialist code reviewers for PR review, producing a tamper-evident review record where every missed finding is traceable to a production incident. Content-driven routing fires capabilities based on code analysis findings — security review fires only when security-sensitive code is detected, not from author labels. Six specialist LLM reviewer agents (security, architecture, style, test coverage, performance, code analysis) analyse diffs in batched token-budget chunks via `StructuredAgentInvoker`. A full merge queue lifecycle handles batch composition with CBR-based risk assessment, four bisection strategies for failure isolation, and priority-lane SLAs. Cross-repo coordinated merges spawn per-repo sub-cases with automatic rollback on fault. Trust-weighted reviewer routing uses Bayesian trust scoring across four quality dimensions (thoroughness, precision, scope calibration, responsiveness), with CBR-enhanced matching where an agent with lower trust but higher precedent match can win over a higher-trust agent.

## Differentiation

- **Content-driven routing, not label-driven:** Code analysis determines which specialist reviews fire. No author-declared labels. Binding conditions evaluate code content — security, architecture crossing, test coverage gaps — not metadata.
- **Trust feedback closed loop:** Production incidents trace back to specific missed reviews via FLAGGED attestations. Trust scores decay on missed findings. No competitor does this — most review tools assign reviewers and forget.
- **Typed capability vocabulary:** Four typed classes (ReviewDomain, AgentQualification, HumanDecision, HumanOversight) with distinct routing semantics. Not a flat tag list — each class has different trust scoring rules and escalation paths.
- **CBR-enhanced reviewer matching:** PR similarity scoring (weighted Jaccard over file paths, modules, languages, change size, contributor) feeds into reviewer selection. Past precedent outcomes influence routing alongside trust scores. CBR weight adjustment from outcome feedback creates a self-improving system.
- **Merge queue with CBR risk assessment:** Batch composition uses CBR precedent lookup for risk scoring. Four bisection strategies for failure isolation (binary, precedent-based, trust-weighted, outlier isolation). Not just a FIFO queue.
- **Reusable case definition template:** `templates/pr-review` module provides an abstract `PrReviewTemplateCaseHub` with base YAML and overlay support, enabling other domains to adopt the PR review pattern with domain-specific merge logic.

## Extent

7 Maven modules (+ root POM). 283 main + 196 test Java source files. 4 YAML case definitions (pr-review, merge-batch, merge-queue-entry, coordinated-change). 6 LLM reviewer agents + 6 stub agents. 14 capabilities across 5 typed classes. 4 trust dimensions. 5 @McpDomain API classes + 3 GraphQL resolvers providing tri-channel API surface. 7 notification event types with 5 notification bridges. 4 bisection strategies. 8 action risk types. Compliance evidence model with Merkle inclusion proofs. GDPR Art.17 erasure with tamper-evident receipts. 6 tutorial layers demonstrating progressive CaseHub adoption.

## Consistency Benefit

- **CDI throughout:** All review agents, trust routing, notification bridges, merge queue, compliance — all CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses CaseHubRuntime (engine), CasePlanModel + YamlCaseHub (engine), TrustWeightedAgentStrategy (engine-ledger), CommitmentLifecycle (qhorus), WorkItemLifecycleEvent (work), SubscribableEvent + NotificationBridge (platform), CaseMemoryStore (neocortex), ActionRiskClassifier (engine), FailureClassifier (engine), TrustBootstrapSource (ledger), PreferenceProvider (platform-config), CurrentPrincipal (platform-oidc), @McpDomain (platform-api), StructuredAgentInvoker (blocks)
- **Tenant-scoped:** Same tenancy model as all other repos — McpIdentityContext, CurrentPrincipal.tenancyId() throughout
- **Audit trail:** MergeDecisionLedgerEntry, ContributorOutcomeLedgerEntry, IncidentFeedback FLAGGED attestations — all through the shared ledger
- **CloudEvent integration:** PrLifecycleEvent and notification events follow the platform event model
- **@McpDomain migration:** Fully migrated to @McpDomain SPI — 5 API classes + 3 resolvers provide REST, GraphQL, and MCP from a single source

## Vision (from open issues)

- **Dynamic review planning (#200):** Engine GOAP / LLM Decomposition — dynamic review planning for complex PRs. LLM decomposes large PRs into review sub-tasks using goal-oriented action planning.
- **Per-domain trust splitting (#178):** Contributor trust scores split by domain (e.g., frontend vs backend vs infra) rather than global per-contributor.
- **Federated contributor trust (#177):** Cross-org score sharing — contributor trust scores portable across organisations.

## E2E Use Case Fragment

**Content-driven PR review with trust feedback loop:** Developer pushes PR → GitHub webhook fires → code analysis agent classifies PR content (security sensitivity, architecture crossing) → engine fires capability bindings based on content (security review, architecture review, style, test coverage) → trust-weighted routing selects reviewers per capability (CBR precedent matching boosts agents with relevant history) → LLM agents analyse diff in batched chunks, produce structured findings → human approval gate with SLA → merge decision recorded to tamper-evident ledger → PR enters merge queue with CBR-based batch risk assessment → batch CI runs → bisection on failure → merge execution → post-merge, production incident traced back to specific missed review → FLAGGED attestation written → reviewer trust score adjusted → future routing adapts.

This is the full Declaration (YAML case definition) → Intelligence (content analysis, CBR retrieval, trust scoring) → Control (engine orchestration, binding conditions) → Convergence (merge queue, coordinated merge) → Operations (incident feedback, trust adjustment) loop applied to software engineering. The feedback loop is the key: every production incident improves future routing.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 4 case types: PR review, merge batch, merge queue entry, coordinated change |
| Trust & Audit | Bayesian trust scoring across 4 dimensions, FLAGGED attestations for incident feedback |
| Case-Based Reasoning | PR similarity scoring, precedent activation, CBR-enhanced reviewer matching, batch risk assessment |
| Work Management | Human approval gate with SLA, escalation tiers, candidate groups |
| Agentic Communications | COMMAND/RESPONSE per reviewer via qhorus, commitment lifecycle |
| Notification Pipeline | 7 event types, 5 bridges, subscription registration |
| GDPR | Art.17 erasure with tamper-evident receipts, contributor memory cleanup |
| Compliance | Audit chain integrity, SLA compliance, trust routing compliance, Merkle inclusion proofs |
| Action Risk Classification | 8 action types, PreferenceProvider-driven thresholds, engine gate lifecycle |
| Failure Classification | Domain-specific failure classifier (transient/knowledge/infeasible) |
| Agent Memory | CaseMemoryStore for contributor history, code-area history, precedent storage |
| Evidential Checker | 4 benchmark variants for attestation quality verification |
| LLM Fleet | Agent config with provider/alias/tier model, StructuredAgentInvoker for batched analysis |
| @McpDomain Tri-Channel | 5 API classes + 3 resolvers generating REST + GraphQL + MCP |
| Preference System | 8 preference key classes covering SLA, CBR, risk, trust, merge queue, notification, memory |
| Reusable Template | PR review case definition template for cross-domain adoption |