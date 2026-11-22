# Human Task Management — Brief Notes

> Source: casehub-work repo audit (2026-10-02)

## Capability Summary

Human Task Management provides a full human-in-the-loop task lifecycle for enterprise automation. Not a simple TODO list — a 12-status lifecycle engine with SLA enforcement, delegation chains, M-of-N group completion, conflict-of-interest exclusion, conditional named outcomes, hierarchical progress tracking, and tamper-evident audit trail.

## Key Capabilities (descriptive names)

- **Task Lifecycle Engine** — 12-status lifecycle (PENDING → ASSIGNED → IN_PROGRESS → terminal states including DELEGATED, SUSPENDED, ESCALATED, OBSOLETE). Terminal status detection via `isTerminal()`/`isActive()`.
- **SLA Enforcement** — breach policies with sealed decision hierarchy (Fail, EscalateTo, Extend, Exhausted, Chained). Business calendar with holiday support (static + iCal). Per-tenant SLA overrides via platform preference hierarchy.
- **Delegation Chains** — pre-acceptance delegation with accept/decline workflow. Configurable decline routing (POOL or DELEGATOR). Full delegation history tracking.
- **M-of-N Group Completion** — multi-instance WorkItems with configurable threshold, 4 assignment strategies (pool, round-robin, explicit, composite), threshold-reached policies (KEEP/CANCEL_REMAINING).
- **Conflict-of-Interest Exclusion** — pluggable exclusion policies returning ALLOW/deny with reason.
- **Conditional Named Outcomes** — JEXL-evaluated outcome conditions, template-snapshotted permitted outcomes.
- **Progress Tracking** — 6-module platform-level progress subsystem. Hierarchical rollup (3 strategies), step-based tracking, event-sourced state, SSE broadcasting. Scope-generic (not tied to WorkItems).
- **Worker Routing** — 3 selection strategies (LeastLoaded, ClaimFirst, RoundRobin). AI-augmented: embedding-based semantic skill matching, worker skill profiles. Learning-ready: `candidateScores` and `routingExperiences` threaded through lifecycle.
- **Audit Trail** — optional ledger integration for tamper-evident audit entries, trust scoring, provenance, attestation. Cross-ledger causal linking.
- **Queue Management** — label-based queue views, filter engine (platform LabelRule), trend snapshots, SSE dashboard.
- **Saga Compensation** — recently completed (#382-396). Compensation bindings, YAML schema, engine-work bridge, ledger supplement, notifications, visualization.
- **External Integration** — issue tracker SPI (GitHub, Jira webhooks), CloudEvent bridge for distributed task creation, Quarkus-Flow workflow DSL.
- **Federation** — cross-cluster WorkItem distribution (module exists, in development).
- **Annotation-Driven Tasks** — `@HumanApproval`, `@RequiresQuorum`, `@Escalate`, `@SkillMatch` for declarative human-in-the-loop.
- **MCP/LLM Discovery** — 25+ REST resources annotated with `@McpDomain` for LLM tool discoverability.

## Differentiation vs Alternatives

| Alternative | What it offers | What Human Task Management adds |
|------------|---------------|--------------------------------|
| JIRA/Linear/Asana | Issue tracking with status | 12-status lifecycle with SLA, delegation, M-of-N, exclusion, outcomes |
| Camunda HumanTask | BPMN human task lifecycle | Same lifecycle depth + SLA breach decision algebra, AI routing, saga compensation, progress tracking |
| AWS Step Functions HumanTask | Basic approval/rejection | No SLA, no delegation chains, no M-of-N, no exclusion policies |
| Custom task queue (Redis/DB) | Flexible but manual | Full lifecycle engine with 17 SPIs, template versioning, audit trail |

**Unique differentiators:**
1. **SLA breach decision algebra** — sealed hierarchy with Chained composition. Not just "escalate on breach" but composable policies.
2. **M-of-N with assignment strategies** — not just "wait for N of M" but configurable assignment distribution and threshold-reached handling.
3. **Progress as separate subsystem** — not coupled to tasks. Any scope type gets hierarchical progress with rollup.
4. **AI-augmented routing** — semantic skill matching, confidence scoring, learning-ready routing context threaded through the entire lifecycle.
5. **Saga compensation** — reversible task chains with compensation bindings, YAML-declared.

## Extent

- **38 child POMs** (22 currently active in reactor, rest excluded for compilation issues #403/#404)
- **196 API types** including 42 SPI interfaces (17 core + progress + issue-tracker)
- **26 lifecycle event types** with corresponding CloudEvent URIs
- **12 WorkItem statuses** (not 11 as previously documented)
- **6 progress modules** (api, core, runtime, deployment, rest, memory)
- **4 persistence backends** (JPA/Postgres, MongoDB/Quarkus, MongoDB/Spring, in-memory)
- **~80 design specs** documenting evolution
- **~120 blog entries** capturing design decisions
- **10 ADRs** covering key architectural choices

## Consistency Benefit

- Shares CDI model, event system, tenancy, audit trail with all CaseHub repos
- `WorkItemLifecycleEvent` flows through platform subscription engine DataSource
- Preference hierarchy integration for per-tenant SLA overrides
- LabelRule evaluation uses platform's shared filter engine
- Cross-system references via `CrossSystemRef` sealed interface (bridges to engine, qhorus)
- NamedStrategy SPI shared with engine strategy resolver

## Vision (from open issues and specs)

- **Spring completeness** (#413): closing gaps for Spring Boot deployments
- **Federation**: cross-cluster WorkItem distribution (module active, implementation ongoing)
- **Saga compensation**: recently landed, further examples and operational tooling planned
- **Progress enhancements**: visualization modes, rollback control, arbitrary JSON schema shapes
- **Event mesh**: distributed WorkItem events across services
- **Queue improvements**: caching, database-level aggregation

## Lifecycle Stage in E2E Story

Human Task Management sits at the **Control** and **Collaboration** stages of the platform lifecycle:
- **Control**: SLA enforcement, oversight gates (ActionGate bridge), compliance-grade audit trail
- **Collaboration**: human-agent handoff, delegation chains, group consensus (M-of-N), conflict-of-interest

The engine-adapter bridge connects it to **Declaration** (case definitions spawn WorkItems) and **Intelligence** (AI-augmented worker routing).

## Guide Corrections Needed

1. **Status count**: consumer guide intro says "11-status lifecycle" but code has 12 (OBSOLETE added). The status table correctly lists all 12.
2. **Module count**: contributor guide says "30 modules" but root POM has 38 child POMs (22 active, 16 excluded).
3. **engine-adapter**: contributor guide still describes it as part of work, but commit 454bee26 moved it to engine repo.
4. **Notifications module**: correctly marked as removed in both guides.
5. **SPI count**: contributor guide says "17 interfaces in api" which matches the code.