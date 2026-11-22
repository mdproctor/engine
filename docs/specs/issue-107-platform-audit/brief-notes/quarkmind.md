# Brief Notes — QuarkMind (Game AI Living Lab)

## Capability Area

**Descriptive name:** Game AI Living Lab & Agency Framework
**Pillar:** Automation (agent coordination + plugin dispatch), Collaboration (LLM advisory + coaching), Coordination (trust-weighted routing + CBR learning)

## What It Does

Multi-game AI agency framework that serves as a living lab for CaseHub platform integration patterns. The primary application (quarkmind-sc2) plays StarCraft II as a plugin platform — coordinating strategy, economics, tactics, and scouting agents via CaseHub's case engine and blackboard, with Drools for rule-based reasoning and Quarkus Flow for durable task execution. The quarkmind-core module extracts a game-agnostic agency framework (AgencyLoop, WorldBridge SPI, needs, personality evolution, spatial contracts) shared across multiple game worlds (SC2, Ville generative agents, stubs for Minecraft/Evennia/Sonaria). The quarkmind-classifier module provides a Python ML pipeline producing ONNX strategy classifiers from 151K ladder replays and IEM10 tournament data. A three-tier cascade (Drools → ONNX → LLM) detects enemy strategy with ≥70% accuracy. Dual-mode human interaction: commentary (reactive + narrative narration) and coaching (position-based compliance verification with trust recording).

## Differentiation

- **Harness generality proof:** The same CaseHub harness pattern (CaseFile blackboard, plugin coordination, adaptive agent selection, trust-weighted routing, CBR experience learning) that coordinates AML investigation and clinical trial monitoring also coordinates real-time game AI agents at millisecond tick granularity — without changing the foundation. This is the strongest proof of generality in the application family.
- **Game-agnostic agency framework (quarkmind-core):** Extracted SPIs (WorldBridge, Intent, IntentQueue, NeedState, AgencyLoop) enable plugging any game world into the CaseHub agency loop. Ville, Minecraft, Evennia, Sonaria adapters all share one agency model. No game AI framework offers this level of harness abstraction.
- **Three-tier cascade classifier:** Drools CEP → ONNX → LLM fallback with confidence revision, temporal decay, counter-indication suppression, and strategy transition detection with hysteresis. Each assessment tagged with source tier. Race-guarded rules prevent cross-race false positives.
- **Full physics emulation:** EmulatedGame provides complete SC2 simulation — probe mining (three-tier saturation model), combat (damage, armour, Hardened Shield), multi-race support (Protoss/Terran/Zerg RaceModels), A* pathfinding, visibility grid, building collision. Enables full agent testing without a live SC2 binary.
- **Generative agents (quarkmind-ville):** Stanford "Generative Agents" style multi-character simulation with need-driven behaviour, personality evolution, reflection-based memory consolidation, and a Godot 4 visual client. Uses the same quarkmind-core agency loop.

## Extent

11 Maven modules (8 top-level + 3 quarkmind-ville sub-modules) + 2 Python packages. 573 main Java source files, 304 test Java files (877 total). 40 Python files in quarkmind-classifier, 33 in quarkmind-dataset. 15+ TypeScript/JS files in the Lit webui. 6 Drools DRL rule files. 68-entry StrategyArchetype enum across 3 races x 3 phases. 82 UpgradeType entries. 12 LLM agent configurations (6 advisory, 4 commentary, 2 coaching). Full Three.js 3D visualizer with 65+ unit/building sprites. Electron desktop wrapper. Docker multi-arch SC2 replay restoration pipeline. 828 unit/integration tests + 288 Playwright E2E tests.

## Consistency Benefit

- **CDI throughout:** Agent orchestration, plugin dispatch, Drools rule units, economics flow, trust scoring, CBR routing, scouting — all CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses CaseHub (engine), CaseFile blackboard, WorkerFunction.Sync (engine), TrustCandidateClassifier (ledger), AgentDescriptor (eidos), CbrCaseMemoryStore (neocortex), qhorus advisory channel (qhorus), summarisation framework (blocks), MockPreferenceProvider (platform)
- **Same harness layers as enterprise apps:** Layers 1-7 (blackboard → qhorus → ledger audit → adaptive selection → trust routing → comparison) — identical progression to AML, clinical, SOC applications
- **Tenant-scoped:** Same tenancy model as all other CaseHub apps
- **@McpDomain integration:** Unified API generation with JAX-RS annotations

## Vision (from open issues)

- **Upgrade research abilLinks (#336):** Wire upgrade research abilities into AbilityMapping for complete replay feature extraction
- **Community replay analysis (#315):** Integrate starcraft2.ai community replays as coaching reference
- **ONNX retraining (#316):** Regenerate SC2EGSet intermediate training data with expanded feature set
- **Multi-world expansion:** Minecraft, Evennia, Sonaria stubs positioned for WorldBridge implementations — same agency framework, different game worlds

## E2E Use Case Fragment

**AI game coaching with learning loop:** Player enters coach mode → game state broadcaster pushes per-tick snapshots → CascadingPatternClassifier detects enemy strategy (Drools CEP first, ONNX if uncertain, LLM fallback) → strategy transition detected via hysteresis → CoachingTriggerBuilder fires on coaching-worthy moment → CoachingSessionSelector picks appropriate agent based on urgency and disposition → LLM coaching worker generates advice with StrategyTaxonomy counter data → player acts → CoachingComplianceEvaluator verifies compliance (army centroid movement, expansion placement, unit positioning) → CoachingEffectivenessTrustRecorder writes trust attestation → SC2CbrRetentionObserver records game outcome features → next game, CBR-routed strategy selection uses accumulated experience → coaching improves over time.

This is the full Declaration → Intelligence → Control → Convergence → Operations loop applied to real-time game AI. The same trust+CBR+advisory architecture runs identically in regulated enterprise domains.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | CaseFile blackboard with 30 keys, TickOrchestratorWorker chains plugins per game tick |
| Agentic Communications | Advisory channel via qhorus for LLM observers; scouting intel broker |
| Trust & Accountability | Four-phase Bayesian Beta trust maturity; milestone-based intra-game trust attestations; coaching effectiveness trust recording |
| Case-Based Reasoning | SC2StrategyRouterTask queries CBR for past game experiences; SC2CbrRetentionObserver records outcomes for cross-game learning |
| Agent Identity | 12 AgentDescriptor configurations via eidos with ConscientiousnessTerm disposition traits; DispositionAwareRoutingStrategy |
| Event Summarisation | Four-level temporal abstraction (ticks → moments → phases → arcs) via blocks framework |
| ONNX Inference | CascadingPatternClassifier uses neocortex InferenceModel for strategy classification |
| Durable Execution | Quarkus Flow for economics build order execution with retry |
| Rule-Based Reasoning | 6 Drools Rule Units for strategy, tactics, scouting, pattern classification, dominance weights, moment detection |
| Shared Platform Services | Identity, preferences, CDI lifecycle, @McpDomain tri-channel API |

## App Profile

```yaml
app_profile:
  domain: "Game AI research and platform integration living lab"
  capabilities:
    - "Real-time StarCraft II AI with plugin-coordinated agents"
    - "Three-tier enemy strategy classification (Drools/ONNX/LLM)"
    - "LLM-powered coaching with position-based compliance verification"
    - "Game-agnostic agency framework (quarkmind-core) for multi-world AI"
    - "Full SC2 physics emulation with multi-race support"
    - "Generative agents simulation (quarkmind-ville)"
  platform_leverages:
    - {capability: "Case Orchestration", usage: "CaseFile blackboard for per-tick plugin coordination"}
    - {capability: "Trust & Accountability", usage: "Bayesian trust for strategy routing and coaching effectiveness"}
    - {capability: "Case-Based Reasoning", usage: "Cross-game strategy learning from outcomes"}
    - {capability: "Agent Identity", usage: "12 agent descriptors with disposition-aware routing"}
    - {capability: "Agentic Communications", usage: "Advisory and scouting intel channels via qhorus"}
    - {capability: "Event Summarisation", usage: "Four-level temporal abstraction for game narratives"}
    - {capability: "ONNX Inference", usage: "Learned enemy strategy classifier in cascade"}
  status: active
```