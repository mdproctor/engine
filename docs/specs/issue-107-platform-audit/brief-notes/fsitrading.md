# Brief Notes — FSI Trading Automation

## Capability Area

**Descriptive name:** Algorithmic Trading Automation
**Pillar:** Automation (strategy execution pipeline), Coordination (multi-agent deliberation + overnight incident response), Collaboration (human oversight gates + on-call escalation)

## What It Does

Multi-agent algorithmic trading platform built on CaseHub. 7 strategy agents (momentum, mean-reversion, statistical arbitrage, market making, event-driven, portfolio rebalance, overnight risk) evaluate market signals through a trust-weighted arena pipeline: routing → parallel evaluation → per-instrument majority voting → risk classification → human approval gate → execution. 5-level temporal summarisation pipeline (tick → 1-min OHLCV → 5-min trend → hourly regime → session narrative) with computational and LLM-powered summarisers. Multi-agent deliberation over qhorus channels with epistemic convergence detection for contested trades. Overnight incident response with severity classification, 13 response agents, SLA-driven escalation, and automated post-mortem. YAML playbooks for flash crash, regime shift, overnight incident, risk escalation, and strategy evaluation cycles. Tamper-evident ledger audit trail with Merkle proofs for MiFID II Art.17, Dodd-Frank, MAR regulatory compliance.

## Differentiation

- **Most comprehensive platform showcase:** Exercises more platform capabilities than any other application — orchestration, trust routing, ledger audit, qhorus deliberation, CBR, work items, pages dashboard, situation awareness, simulation, MCP tri-channel, YAML playbooks. If a platform capability exists, fsitrading probably uses it.
- **Trust-weighted strategy selection:** Bayesian Beta trust scores learned from P&L attestations. Strategies automatically deprioritised when performance degrades. Not a manual configuration — the system learns from outcomes.
- **Regulatory compliance as structural requirement:** MiFID II Art.17 audit trail is a ledger causality chain (MarketEvent → StrategyEvaluation → OrderExecution), not a logging add-on. Dodd-Frank pre-trade risk gates are ActionRiskClassifier SPIs. MAR abuse detection on the compliance grid. GDPR erasure built in.
- **YAML playbook orchestration:** 5 playbooks with state machines, quorum-based multi-strategy evaluation, parallel LLM sentiment assessment, risk gates, and order semaphore management. Declarative trading automation without code changes.
- **Epistemic deliberation for trading:** Strategy agents debate on qhorus channels with convergence detection (CONSENSUS/DEADLOCK/PROGRESSING/DIMINISHING_RETURNS/CONVERGING). Not a simple vote — structured multi-round debate with common ground tracking and automated post-mortem.

## Extent

2 Maven modules (api + app). 231 main Java sources + 121 test sources. 3 YAML case definitions (strategy-evaluation, overnight-incident, fsi-oversight). 5 YAML playbooks. 16 step definitions across 4 invoke types (MCP, REST, agent, process). 2 shared yaml-core modules (risk-gate, parallel-assessment). 7 strategy agents + 13 incident response agents. 5 simulation corpus files + 4 scenario profiles. 5 prose resolution documents. 11 @McpDomain API classes with ~40 operations (tri-channel: REST + GraphQL + MCP). ~55 REST endpoints. 22 WebSocket push topics across 5 domains (market, trading, deliberation, incident, work-item). Model seed catalog with 3 LLM tiers. Two dock-workbench pages (Trading Desk + Ops Centre) with 25+ UI panels. Dual-datasource (default + qhorus).

## Consistency Benefit

- **CDI throughout:** Strategy agents, arena pipeline, incident response, deliberation, CBR, compliance, ledger — all CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses RoutingSignalAssembler (engine), LedgerEntryRepository (ledger), LedgerAttestation + TrustExportService (ledger), WorkBroker + HumanTaskTarget (work), Channel + MessageObserver (qhorus), AgentDescriptorRegistrar (eidos), EventBroadcaster (pages-push), CaseRetriever (neocortex), SimulationEligible + SimulationCorpus (platform-simulation), @McpDomain (platform)
- **Tenant-scoped:** Same tenancy model as all other repos
- **Audit trail:** LedgerExecutionListener for orchestration audit + domain-specific OrderExecutionLedgerEntry/StrategyEvaluationLedgerEntry/DeliberationDecisionLedgerEntry — same Merkle chain integrity as platform ledger
- **CloudEvent integration:** SecurityEvent → CloudEvent via MarketConditionCloudEventPublisher, same event model as platform
- **YAML playbooks:** Same yaml-core step format, shared modules, simulation infrastructure as all other CaseHub applications

## Vision (from open issues)

- **E2E adaptive playbook demonstration (#64 epic):** Full scenario → detect → respond (playbook) → learn (CBR) → adapt loop running end-to-end. Closes the CBR feedback loop from playbook execution outcomes back to playbook selection.
- **CBR plan-to-playbook mapping (#60):** Convert CBR-retrieved past plans to yaml-core step sequences. Historical incident responses become reusable playbooks.
- **Goal-modulated playbook selection (#61):** Agent goals influence which playbook runs — defensive goals favour conservative playbooks, aggressive goals favour opportunistic ones.
- **CBR execution outcome recording (#59):** Bridge yaml-core step outcomes into CBR store for systematic learning from playbook execution.
- **Upstream alignment (#65):** Align with landed platform/engine changes before next demo work.

## E2E Use Case Fragment

**Flash crash overnight response:** Market regime detection fires RegimeChanged event with >5% drop → flash-crash-response playbook activates with 120-second deadline → parallel risk assessment + FIX session health check → order halt semaphore engaged → parallel LLM sentiment analysis from 3 perspectives (exposure, regulatory, execution) → risk gate evaluates each exposed position → approved positions closed, high-risk positions escalated to on-call trader work item with SLA → monitoring loop polls risk until stabilised → orders released → incident written to CBR store → similar future flash crashes retrieve this response and adapt it.

This exercises Declaration (YAML case + playbook), Intelligence (CBR retrieval + LLM sentiment), Control (risk gate + order semaphore + human approval), Convergence (monitoring loop + position reconciliation), and Operations (post-mortem + CBR retain) — the full lifecycle spine.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 3 case types (strategy-evaluation, overnight-incident, fsi-oversight) |
| YAML Playbooks | 5 playbooks with state machines, quorum, parallel assessment |
| Multi-Agent Arena | 7 strategy agents with trust-weighted routing through 6 signal strategies |
| Agent Deliberation | Multi-round qhorus debate with epistemic convergence for contested trades |
| Trust & Accountability | Bayesian Beta from P&L attestations, Merkle-chained ledger entries |
| Human Task Management | Risk gate work items with SLA, 2-tier escalation policy |
| Case-Based Reasoning | 6 feature extractors, plan adaptation, similarity retrieval, outcome recording |
| Situation Awareness | Market event detection (trend reversal, regime change) via CDI events |
| Temporal Summarisation | 5-level pipeline (tick → OHLCV → trend → regime → narrative) |
| Simulation Framework | 4 SPI decorators, 5 corpus files, 4 scenario profiles, temporal injection |
| Pages Dashboard | 2 dock-workbench pages, 25+ panels, WebSocket push, layout persistence |
| MCP Tri-Channel | 11 @McpDomain API classes, ~40 operations (REST + GraphQL + MCP) |
| Regulatory Compliance | MiFID II Art.17 audit, Dodd-Frank risk gates, MAR abuse detection, GDPR erasure |
| Agent Identity | 7 strategy agents registered via eidos AgentDescriptorRegistrar with disposition profiles |
| Model Selection | Tier-based model registry (FLAGSHIP/STANDARD/FAST) per agent capability |
| Notification Pipeline | Incident alerts and SLA escalation via NotificationDispatcher |