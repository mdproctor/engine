# Brief Notes — Agentic Orchestration & Building Blocks

> Source repo: blocks (25 modules, 903 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Compositional Agentic Orchestration
Full multi-agent orchestration framework built from five composable SPIs (routing, decomposition, activation, aggregation, termination). Eight topology patterns (supervisor, sequence, loop, parallel, voting, debate, conditional, HTN) via builder DSL. Two execution drivers: OrchestratedDriver (imperative while-loop) and ChoreographedDriver (event-reactive). All patterns expressible in both Java and YAML.
- **Differentiation:** No competitor offers compositional SPI-based orchestration with this range. LangChain/CrewAI/AutoGen provide fixed topologies; CaseHub lets you mix routing, aggregation, and termination strategies independently. The YAML surface makes agentic patterns accessible to non-programmers. GOAP backward-chaining decomposition and forward-reasoning (SHOP-style) in the same framework is unique.
- **Extent:** 9 decomposition strategies (identity through GOAP), 4 routing strategies + LLM-selected, 4 termination conditions + judge convergence, accountability listeners for EU AI Act Art.12 compliance, HTN with DAG cycle validation. 8 pattern builders with shared AbstractPatternBuilder base.

### 2. BDI Agent Intelligence
Belief-Desire-Intention architecture integrated into the orchestration framework. Structured beliefs with consistency checking, joint intentions with reconsideration signals, coalition formation with capability coverage evaluation, and a judgment framework with consensus policies.
- **Differentiation:** No orchestration framework in Java has BDI primitives integrated alongside practical execution patterns. These aren't academic abstractions — they compose with the routing and termination SPIs. Joint intentions bridge to coalition formation; beliefs feed decomposition context.
- **Extent:** belief (Belief, BeliefSet, ConsistencyChecker), intention (JointIntention, IntentionMonitor, ReconsiderationSignal), coalition (CoalitionEvaluator, CapabilityCoverageEvaluator), judgment (JudgmentDispatcher, 5 AgreementPolicy variants, CompositeVerifier).

### 3. Structured Conversation Protocol
Reusable multi-agent deliberation infrastructure extracted from drafthouse. ConversationProjection folds channel messages into ConversationState. Epistemic common ground analysis (established/pending/disputed facts). Convergence detection (5 states: PROGRESSING through DEADLOCK). Negotiation protocol with acceptance policies (unanimous, majority, threshold). Autonomous conversation orchestrator with pluggable turn policies.
- **Differentiation:** Epistemic common ground analysis is novel — no competitor tracks what agents actually agree on vs dispute. Convergence detection as a formal concept (not just "max iterations") enables genuinely adaptive multi-agent conversations. The negotiation protocol with M-of-N consensus gates composes naturally with qhorus channels.
- **Extent:** 27 conversation types, 5 convergence states, 3 epistemic rules (explicit acknowledgement, tacit acceptance, commitment resolution), 4 turn policies (round-robin, addressed, point-addressed, free), bilateral and multilateral negotiation with acceptance policies.

### 4. Temporal Event Summarisation
Multi-level event accumulation with configurable window policies, compactors, and pluggable summarisers. Content summarisation SPI with tiered routing (verbatim/grouped/LLM). Decision narrative pipeline (two-level: heuristic L1 → LLM L2). Observation pipeline for demand-driven agent prompt assembly with affordance grounding.
- **Differentiation:** The tiered summarisation architecture (heuristic → grouped → LLM) with stateful emission policies and state store write-through caching is production-ready infrastructure that competitors implement ad-hoc. Decision narrative pipeline transforms raw platform signals into human-readable explanations — accountability as infrastructure. Affordance grounding (ObservableEntity + ActionDescriptor rendering) gives LLM agents structured world knowledge.
- **Extent:** EventAccumulator → Compactor → Summariser → EventStreamBus pipeline. Keyed variant for per-entity summarisation. 5 summarisation patterns documented. Decision narrative with 6 signal types. Observation pipeline with 3 rendering tiers.

### 5. Social Cognition Framework
Complete social intelligence stack for cognitive agents: mood (PAD model), drives (4 axes: curiosity, competence, affiliation, autonomy), personality evolution, mental model (BDI Theory of Mind), user model (familiarity, relationship stages), strategy learning, inner life (proactive initiation), narrative identity (first-person autobiography), goal proposal (drive-to-goal mapping with escalation), social emergence (norm detection, collective goal formation), needs pyramid, temporal focus, reflection retrieval, engagement tracking, and consolidation mediation.
- **Differentiation:** Nothing comparable exists. CrewAI has role strings; CaseHub has a full cognitive architecture with mood modulation of drives, narrative identity construction, governed goal escalation, social norm emergence detection, and collective goal formation. The entire stack renders as PromptSections for LLM agent consumption — social cognition as infrastructure, not application logic.
- **Extent:** 90+ types across social sub-packages. 10+ PromptSection implementations. Drive modulation pipeline (raw → mood → personality → clamping → composition). Goal escalation with cross-axis composition and narrative alignment. JPA persistence for all stores (Quarkus + Spring).

### 6. AI-Powered Agent Routing
LLM-reasoned and CBR-evidence routing strategies for the engine's routing pipeline. Five RoutingSignalProviders (plan-composition, predecessor, coordination, disposition). Trust filtering with four-phase maturity model. Composable prompt enrichment with configurable char budget.
- **Differentiation:** Composable signal-based routing that blends LLM reasoning, CBR evidence, trust scores, plan history, coordination outcomes, and personality disposition into a single routing decision. No competitor has this — most use static assignment or simple LLM selection.
- **Extent:** 2 routing strategies (LLM, CBR), 5 signal providers, 3 outcome weight SPIs with defaults, prompt construction with candidate cards, trust filtering pipeline.

### 7. Prompt Optimisation
Framework for automated prompt variant generation, few-shot example selection, and runtime prompt customisation. Instruction optimiser (LLM-driven), few-shot optimiser, diversity strategies, variant store, and runtime integration via SystemPromptCustomiser.
- **Differentiation:** Self-improving prompts as a reusable framework — not application-specific. Combines instruction optimisation, few-shot selection with diversity strategies, and outcome-aware variant selection. The runtime integration (VariantAwareSystemPromptCustomiser) means prompt improvements deploy automatically.
- **Extent:** 2 optimiser impls, 2 diversity strategies, variant store SPI, runtime section/customiser, optimisation dataset management.

### 8. Agent Memory Hygiene
Tick-driven memory maintenance: reflection storage, confidence scoring (arousal, surprise, weighted composite), integrity checking (structural + semantic), retention management. Exposes knowledge gaps for curiosity drive source.
- **Differentiation:** Memory as managed infrastructure with automated hygiene — confidence decay, integrity violations, retention purge. Knowledge gaps feed the drive system, creating a closed loop: memory quality → curiosity → exploration → new knowledge. Most frameworks treat memory as append-only storage.
- **Extent:** MemoryHygieneOrchestrator, 4 confidence scorers, structural + semantic integrity checkers, retention config, reflection query/store SPIs.

### 9. Trust Intake & Vouching
Classification of new entities and managed vouching between trusted entities.
- **Differentiation:** Trust as intake classification + social vouching, not just scores. VouchConstraints enforce eligibility rules. Composes with ledger trust scores and engine routing policies.
- **Extent:** IntakeClassifier SPI, VouchService SPI, VouchConstraint SPI, eligibility checking.

### 10. Speech Pipeline
Provider-agnostic speech SPIs (STT/TTS), WebSocket server, sherpa-onnx FFM implementation (JDK 22+), AvatarCognition SPI bridging social cognition into speech sessions. Cross-platform native binaries for 5 targets.
- **Differentiation:** Speech as infrastructure with social cognition integration. AvatarCognition wires all cognitive orchestrators into the speech pipeline — mood, drives, mental models, strategy, narrative all influence the avatar's speech. Proactive initiation via InnerLifeOrchestrator. Per-phoneme timing for lip-sync.
- **Extent:** 3 API types, WebSocket server module, sherpa-onnx FFM impl, 5 native modules, AvatarCognition SPI with SocialAvatarCognition CDI impl.

## Cross-Repo Patterns

- **Engine integration:** blocks provides the agentic orchestration framework that engine consumes for multi-agent execution. ExecutionModel, ExecutionDriver, AgentRef, RoutingCandidate flow from blocks → engine.
- **Qhorus composition:** ConversationProjection and NegotiationProjection implement qhorus ChannelProjection<S>. Channel utilities (ChannelAgentDispatcher, BoundedProjectionDecorator, ChannelEventAdapter) bridge qhorus channels to blocks orchestration.
- **Ledger accountability:** LedgerExecutionListener records EU AI Act Art.12 compliance events. Trust routing types create audit trails for routing decisions. AttestationIntentWriter SPI bridges to ledger-backed attestation.
- **Neocortex cognitive bridge:** CognitiveAttentionMediator observes neocortex attention signals. ReflectionRetrievalOrchestrator queries neocortex reflection stores. ConsolidationMediator bridges neocortex consolidation events. Social cognition orchestrators are being migrated to neocortex (#303, #328).
- **Eidos identity:** DispositionAwareRouting uses eidos AgentDisposition for personality-matched routing. Drive modulation uses AgentDescriptor.disposition() weights.
- **Drafthouse:** ConversationOrchestrator extracted from drafthouse debate infrastructure. DebateProtocol delegates to ConversationProtocol.

## Vision

- **LLM swarm coordination with experience replay** (#285) — multi-agent swarms that learn from collective experience
- **LLM-driven observation & local rules** (#284) — LLM interprets environment and decides actions via local rule systems
- **Social-JPA migration to neocortex** (#328) — completing the cognition migration arc
- **Spring completeness** (#329) — closing remaining Spring gaps (auto-config generation, integration tests)
- **Cognitive emotion architecture completion** (#298) — OCC completion, mood bridge, repo reorganisation