# Agent Identity & Behaviour — Brief Notes

## Capability Area

**Descriptive name:** Agent Identity & Behaviour
**Pillar:** Coordination
**Lifecycle stage:** Intelligence + Control (agents carry structured identity that informs dispatch decisions and prompt rendering)

## What It Does

Structured agent identity system — every AI agent on the platform has a machine-readable descriptor covering identity, capabilities, behavioural disposition, goals, constraints, voice, and organisational membership. The platform uses these descriptors for capability-aware dispatch, personality-consistent prompt rendering, trust-weighted selection, and behavioural compliance tracking.

## Capabilities Discovered

- **Structured agent descriptors** — 4-layer identity model (identity, capabilities, disposition, goals/constraints) with vocabulary-grounded fields
- **Capability health probing** — 7-step sealed status hierarchy (Ready → Degraded → Overloaded → Unavailable → Excluded → EpistemicallyWeak → BehavioralViolation) evaluated at dispatch time
- **Personality framework (JPAF)** — Jungian cognitive function modelling with 8 functions, 16 MBTI types, disposition health probing, and personality evolution (drift detection, function swap, structural reorganisation)
- **Cross-vocabulary subsumption** — XKOS-style hierarchy matching across 12+ vocabulary systems (Jungian, MBTI, DISC, Belbin, Big Five, Enneagram, SDI, Thomas-Kilmann, archetypes, model tiers, capabilities, slots)
- **Multi-format prompt rendering** — MARKDOWN (Claude), PROSE (OpenAI/Gemini/etc), A2A_CARD (machine-readable JSON). Two-stage pipeline with optional LLM semantic enrichment
- **Voice profiles** — structured speech characteristics with persona inheritance (register, accent, catchphrases, speech patterns, vocabulary preferences, quirks)
- **Goal lifecycle management** — BDI-inspired goals with lifecycle states (ACTIVE → BLOCKED/DEFERRED → COMPLETED/ABANDONED/DORMANT) and temporal horizon (IMMEDIATE → ASPIRATIONAL)
- **Behavioural compliance** — learned signal accumulation (DECLINE/SUCCESS/COMPLIANT/VIOLATED) with per-tenancy thresholds, per-dimension and aggregate violation detection
- **Archetype system** — 48 sub-archetypes in 12 families from Hartwell & Chen, with auto-derivation from personality framework values and avatar code generation
- **Organisational model** — hierarchical units, agent relationships (SUPERVISES, DELEGATES_TO, ESCALATES_TO, REPORTS_TO, BACKS_UP), compositional DSL, YAML surface, desiredstate integration
- **Knowledge graph** — JPA-backed task/outcome/attestation tracking with Wilson lower-bound agent ranking
- **Annotation-driven registration** — build-time code generation from `@Identity`, `@Disposition`, `@AgentGoals` etc., with vocabulary validation
- **YAML-core preprocessing** — variables, forEach expansion, when conditions, CSV data sources for descriptor YAML
- **Eval harness** — 10 judges (structural, proximity, disposition presence, MBTI alignment, function activation, personality evolution), 18 profiles, multi-backend (Claude, Ollama, TornadoVM, Jlama)

## Differentiation

**No competitor has this.** Agent frameworks like LangChain, CrewAI, and AutoGen have ad-hoc agent definitions — string-based role descriptions with no structured identity, no formal capability matching, no behavioural contract enforcement, no vocabulary-grounded subsumption, no personality evolution.

Key differentiators vs alternatives:
- **Structured over string-based** — agent identity is a typed, validated, vocabulary-grounded record, not a prompt string
- **Subsumption matching** — OWLS-MX-inspired capability matching across vocabulary hierarchies (Exact/Plugin/Specialization), not string equality
- **Behavioural contracts** — the descriptor IS the contract; compliance is tracked and enforced, not hoped for
- **Personality evolution** — agents evolve through cognitive function activation, not just static profiles
- **Multi-format rendering** — one descriptor serves Claude, OpenAI, and machine-to-machine (A2A)

## Extent

14 modules, 303 Java source files. Not a thin wrapper:
- Full JPA persistence with Flyway migrations (V1 + V3)
- 12+ vocabulary implementations with cross-vocabulary equivalence
- 48-archetype personality system with framework intersection
- 10-judge eval harness with multi-backend LLM support
- Organisational model with hierarchy, relationships, and desiredstate reconciliation
- Template system with YAML preprocessing (variables, forEach, when, CSV)

## Consistency Benefit

- Tenancy-scoped throughout — every query, every registration
- CDI bean ladder (NoOp → InMemory → JPA) follows platform conventions
- PreferenceProvider-based thresholds (per-tenancy resolution via ancestor-chain walk)
- Ledger integration for compliance attestations
- Engine integration for dispatch-time health probing
- Platform-api dependency for ModelQuery (model selection)

## Vision (from open issues)

- **#183** — Add description field to AgentVoiceProfile for layered voice authoring (open)
- **#175** — Standardised runnable demo launchers with live LLM invocation (epic, open)
- **#182** — AgentInvokeHandler — wiring descriptor resolution + AgentProvider invocation (closed, sets up direct invocation path)

## Cross-Repo Patterns

- Engine's `WorkOrchestrator` calls `CapabilityHealth.probe()` at dispatch time
- Desiredstate uses `AgentDescriptorComparator` for drift detection
- Ledger receives compliance attestations via `ComplianceAttestations`
- Blocks/claudony/openclaw use agent descriptors for LLM prompt generation