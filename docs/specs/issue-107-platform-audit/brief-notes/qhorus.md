# Brief Notes — Agentic Communications Mesh

> Source repo: qhorus | Audit date: 2026-10-02 | Wave: 1 (foundation)

## Capability Summary

The Agentic Communications Mesh is the normative communication infrastructure for multi-agent systems. Every agent interaction gets the formal status of an accountable act — grounded in speech act theory, deontic logic, and social commitment semantics.

## Key Capabilities (descriptive names)

- **Speech Act Messaging** — 10-type message taxonomy (QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, PROPOSE, EVENT) rooted in speech act theory. Each type carries semantic obligations enforced by the platform.
- **Commitment Lifecycle** — obligation tracking from OPEN through ACKNOWLEDGED to terminal states (FULFILLED, FAILED, DECLINED, DELEGATED, EXPIRED). Automatic deadline enforcement.
- **Channel Architecture** — typed channels with 5 semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE, BROADCAST), spaces hierarchy, topics, reactions, presence tracking, membership management.
- **Dispatch Gate Pipeline** — 12-step enforcement pipeline for every message: paused check → ACL → rate limiting → capability routing → trust gate → type policy → correlation integrity → protocol evaluation → enforcement gate → overwrite → ledger → fan-out. No bypass path.
- **Protocol Enforcement** — pluggable channel protocols (REQUEST_RESPONSE, TASK_COMPLETION, ROUND_ROBIN, CONTRIBUTION_REQUIRED) with advisory/blocking/quarantine enforcement modes and severity-aware escalation.
- **Normative Accountability** — 4-layer framework: illocutionary (what was said), commitment (what was obligated), temporal (when it expires), enforcement (orchestration reacts).
- **Tamper-Evident Audit** — every message creates a Merkle hash-chained ledger entry via casehub-ledger. Attestation on commitment outcomes. Peer review (endorse/challenge) on attestations.
- **Compliance Evidence Export** — EU AI Act compliance reporting with attribution, obligation, violation, trust history, provenance, and judgment reports. PDF/A-2b with PAdES signing, JSON/CSV with CAdES detached signatures.
- **Watchdog Monitoring** — 11 condition types detecting coordination pathologies: barrier stuck, approval pending, agent stale, channel idle, queue depth, context pressure, loop detected, obligation fan-out, conversation stall, echo chamber, circular delegation.
- **A2A Protocol Bridge** — Google A2A-compatible agent discovery (/.well-known/agent-card.json), task messaging, SSE streaming, push notifications, agent card JWS signing with Ed25519.
- **Channel Gateway** — backend-agnostic fan-out with 3 backend types (agent, human-participating, human-observer), AT_LEAST_ONCE delivery with cursor tracking and reconciliation.
- **Channel Summary** — per-channel maintained summaries with LLM-hookable SummaryUpdateHook SPI, annotations, scheduled sweeps.
- **Capability-Based Routing** — trust-weighted routing via `role:` prefixed targets using eidos AgentSelector. Per-channel trust thresholds.

## Differentiation

- **No competitor has normative accountability.** LangChain, CrewAI, AutoGen — all fire-and-forget messaging. No commitment tracking, no obligation enforcement, no tamper-evident audit trail on agent communication. The Agentic Communications Mesh makes every agent message an accountable speech act.
- **Protocol enforcement is unique.** No other agent framework validates message sequences against configurable protocols with advisory/blocking/quarantine modes. This enables governance over agent conversations — not just logging.
- **Compliance evidence generation.** EU AI Act compliance reporting with digitally signed evidence exports. No agent framework offers this.
- **11 watchdog condition types** for detecting coordination pathologies. The platform doesn't just record what happened — it actively monitors for dysfunction (echo chambers, circular delegation, conversation stalls).
- **A2A protocol bridge** means this mesh participates in the emerging agent interoperability standard while adding governance that A2A alone doesn't provide.

## Extent

- 23 active modules (from API layer through Quarkus extension to Spring variant)
- 29 store interfaces, 30 SPI interfaces
- 12-step dispatch pipeline — not a thin wrapper
- Compliance reporting module with render → sign → store pipeline across 4 formats
- Spring support being extracted (runtime-core + runtime-spring + REST controller generation)
- 1035+ tests
- 55-chapter ARC42STORIES architecture document

## Consistency Benefit

- **Ledger integration** — every message creates a tamper-evident ledger entry via casehub-ledger's Merkle hash chain. Same audit infrastructure used by engine, work, and all application repos.
- **CDI model** — all SPIs follow the `@DefaultBean` / `@Alternative @Priority` pattern used across the platform. Extension points are discovered, not registered.
- **Tenancy** — tenant-scoped stores with cross-tenant admin variants. Same multi-tenancy model as engine and work.
- **Identity** — `CurrentPrincipal` integration via platform-api. Same identity model everywhere.
- **Event model** — CDI events for commitment state changes, protocol evaluations, watchdog alerts, channel lifecycle. Same event-driven pattern used throughout CaseHub.

## Cross-Repo Patterns

- Engine consumes commitment state changes via CDI events for orchestration decisions
- Blocks uses channel projections and summaries for agentic pattern coordination
- Claudony embeds the full mesh for CLI-based agent communication
- Connectors bridge external platforms (Slack, Discord, Teams) into mesh channels
- Eidos provides capability-based routing via AgentSelector

## Vision (from open issues)

- **Qhorus Mesh (#465)** — local relay for LLM-to-LLM communication. Direct inter-agent messaging without going through REST/external channels.
- **YAML channel config (#464)** — channels declared in app.yaml manifest, wired at startup. Enables YAML-first channel configuration for the YAML Application Framework.
- **Spring completeness (#466)** — closing remaining gaps for Spring Boot consumption. Already have runtime-core extraction and REST controller generation.

## Stale Claims Fixed

- Contributor-index claimed 14 modules → actual 23
- Contributor-index claimed 13 store interfaces → actual 29
- Contributor guide was missing 9 modules (a2a-protocol, a2a-outbound, a2a-push-notification, compliance-report, graphql, push, runtime-core, runtime-spring, qhorus-rest-spring)