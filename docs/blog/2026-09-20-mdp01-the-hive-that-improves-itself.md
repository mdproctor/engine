---
title: The Hive That Improves Itself
date: 2026-09-20
author: Mark Proctor
tags: [casehub, engine, stigmergy, swarm, self-improvement, evolution, safety]
projects: [casehub-engine]
---

# The Hive That Improves Itself

Ants don't have managers. No central planner assigns them to trails. An ant drops a pheromone, another ant follows it, the trail strengthens through reinforcement, and a colony-level intelligence emerges from agents that never directly communicate. The pattern is called stigmergy — indirect coordination through the shared environment — and it turns out to be a surprisingly practical foundation for software agent coordination.

Over the past week I've built a complete stigmergy-to-evolution stack in CaseHub's engine: eleven issues, from environment observation through swarm execution to a continuous self-improvement loop with regression detection, circuit breakers, and a structured research pipeline. The system can bootstrap from nothing, grow its own capabilities, detect when its improvements make things worse, and roll them back — all with a human operator watching every decision through a command centre.

This isn't theoretical. It compiles, it has 296 passing tests, and every safety gate is exercised.

## From Pheromones to Proposals

The foundation is signals. CaseHub agents deposit pheromone-like signals into a shared `SignalRegistry` — temporal values that decay naturally and strengthen through reinforcement from multiple agents. When enough agents independently notice the same thing (a stale dependency, a lint violation, a coverage gap), the signals reach consensus, and the system proposes an improvement.

<svg viewBox="0 0 800 200" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr1" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6" fill="#475569"/></marker>
  </defs>
  <rect x="10" y="60" width="130" height="80" rx="8" fill="#f1f5f9" stroke="#94a3b8" stroke-width="1.5"/>
  <text x="75" y="95" text-anchor="middle" font-size="13" fill="#334155" font-weight="600">Agent A</text>
  <text x="75" y="115" text-anchor="middle" font-size="11" fill="#64748b">observes staleness</text>
  <rect x="180" y="60" width="130" height="80" rx="8" fill="#f1f5f9" stroke="#94a3b8" stroke-width="1.5"/>
  <text x="245" y="95" text-anchor="middle" font-size="13" fill="#334155" font-weight="600">Agent B</text>
  <text x="245" y="115" text-anchor="middle" font-size="11" fill="#64748b">observes staleness</text>
  <rect x="350" y="40" width="140" height="120" rx="8" fill="#e0f2fe" stroke="#0284c7" stroke-width="1.5"/>
  <text x="420" y="75" text-anchor="middle" font-size="13" fill="#0c4a6e" font-weight="600">Signal Registry</text>
  <text x="420" y="95" text-anchor="middle" font-size="11" fill="#0369a1">deposit + decay</text>
  <text x="420" y="115" text-anchor="middle" font-size="11" fill="#0369a1">reinforcement</text>
  <text x="420" y="135" text-anchor="middle" font-size="12" fill="#0c4a6e" font-weight="600">→ consensus</text>
  <rect x="540" y="60" width="130" height="80" rx="8" fill="#dcfce7" stroke="#16a34a" stroke-width="1.5"/>
  <text x="605" y="95" text-anchor="middle" font-size="13" fill="#14532d" font-weight="600">Proposal</text>
  <text x="605" y="115" text-anchor="middle" font-size="11" fill="#166534">goal formed</text>
  <line x1="140" y1="100" x2="175" y2="100" stroke="#475569" stroke-width="1.5" marker-end="url(#arr1)"/>
  <line x1="310" y1="100" x2="345" y2="100" stroke="#475569" stroke-width="1.5" marker-end="url(#arr1)"/>
  <line x1="490" y1="100" x2="535" y2="100" stroke="#475569" stroke-width="1.5" marker-end="url(#arr1)"/>
</svg>

The key insight: no agent proposes an improvement directly. Agents observe. They deposit signals about what they notice. The system aggregates those observations and proposes improvements only when independent agents converge on the same conclusion. This is fundamentally different from a central quality scanner — the detection is distributed, the consensus is emergent, and false positives get filtered by requiring multiple independent observations.

## The Gate Pipeline

A proposal reaching consensus is necessary but not sufficient. Between detection and action sits a pipeline of safety gates — each gate can block the proposal, and each exists because of a specific failure mode we wanted to prevent.

<svg viewBox="0 0 800 340" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr2" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6" fill="#475569"/></marker>
    <marker id="arrRed" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6" fill="#dc2626"/></marker>
  </defs>
  <!-- Gates -->
  <rect x="10" y="10" width="120" height="50" rx="6" fill="#fef2f2" stroke="#dc2626" stroke-width="1.5"/>
  <text x="70" y="32" text-anchor="middle" font-size="11" fill="#991b1b" font-weight="600">Opt-in Check</text>
  <text x="70" y="47" text-anchor="middle" font-size="10" fill="#b91c1c">evolution enabled?</text>

  <rect x="150" y="10" width="120" height="50" rx="6" fill="#fff7ed" stroke="#ea580c" stroke-width="1.5"/>
  <text x="210" y="32" text-anchor="middle" font-size="11" fill="#9a3412" font-weight="600">Health Refresh</text>
  <text x="210" y="47" text-anchor="middle" font-size="10" fill="#c2410c">score all areas</text>

  <rect x="290" y="10" width="120" height="50" rx="6" fill="#fefce8" stroke="#ca8a04" stroke-width="1.5"/>
  <text x="350" y="32" text-anchor="middle" font-size="11" fill="#854d0e" font-weight="600">Regression</text>
  <text x="350" y="47" text-anchor="middle" font-size="10" fill="#a16207">check monitors</text>

  <rect x="430" y="10" width="120" height="50" rx="6" fill="#fef2f2" stroke="#dc2626" stroke-width="1.5"/>
  <text x="490" y="32" text-anchor="middle" font-size="11" fill="#991b1b" font-weight="600">Circuit Breaker</text>
  <text x="490" y="47" text-anchor="middle" font-size="10" fill="#b91c1c">OPEN → block</text>

  <rect x="570" y="10" width="120" height="50" rx="6" fill="#f0fdf4" stroke="#16a34a" stroke-width="1.5"/>
  <text x="630" y="32" text-anchor="middle" font-size="11" fill="#14532d" font-weight="600">Consensus</text>
  <text x="630" y="47" text-anchor="middle" font-size="10" fill="#166534">signal scan</text>

  <!-- Row 2 -->
  <rect x="10" y="90" width="120" height="50" rx="6" fill="#fdf4ff" stroke="#a855f7" stroke-width="1.5"/>
  <text x="70" y="112" text-anchor="middle" font-size="11" fill="#6b21a8" font-weight="600">Category</text>
  <text x="70" y="127" text-anchor="middle" font-size="10" fill="#7e22ce">suppressed?</text>

  <rect x="150" y="90" width="120" height="50" rx="6" fill="#fdf4ff" stroke="#a855f7" stroke-width="1.5"/>
  <text x="210" y="112" text-anchor="middle" font-size="11" fill="#6b21a8" font-weight="600">Anti-oscillation</text>
  <text x="210" y="127" text-anchor="middle" font-size="10" fill="#7e22ce">recently reverted?</text>

  <rect x="290" y="90" width="120" height="50" rx="6" fill="#eff6ff" stroke="#2563eb" stroke-width="1.5"/>
  <text x="350" y="112" text-anchor="middle" font-size="11" fill="#1e40af" font-weight="600">Budget</text>
  <text x="350" y="127" text-anchor="middle" font-size="10" fill="#1d4ed8">limits + deny list</text>

  <rect x="430" y="90" width="120" height="50" rx="6" fill="#eff6ff" stroke="#2563eb" stroke-width="1.5"/>
  <text x="490" y="112" text-anchor="middle" font-size="11" fill="#1e40af" font-weight="600">Conflict</text>
  <text x="490" y="127" text-anchor="middle" font-size="10" fill="#1d4ed8">path overlap?</text>

  <rect x="570" y="90" width="120" height="50" rx="6" fill="#dcfce7" stroke="#16a34a" stroke-width="2"/>
  <text x="630" y="112" text-anchor="middle" font-size="12" fill="#14532d" font-weight="700">Propose Goal</text>
  <text x="630" y="127" text-anchor="middle" font-size="10" fill="#166534">→ case spawned</text>

  <!-- Arrows row 1 -->
  <line x1="130" y1="35" x2="147" y2="35" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="270" y1="35" x2="287" y2="35" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="410" y1="35" x2="427" y2="35" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="550" y1="35" x2="567" y2="35" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>

  <!-- Down from consensus -->
  <line x1="690" y1="50" x2="690" y2="75" stroke="#475569" stroke-width="1.5"/>
  <line x1="690" y1="75" x2="10" y2="75" stroke="#475569" stroke-width="1.5"/>
  <line x1="10" y1="75" x2="10" y2="87" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>

  <!-- Arrows row 2 -->
  <line x1="130" y1="115" x2="147" y2="115" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="270" y1="115" x2="287" y2="115" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="410" y1="115" x2="427" y2="115" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>
  <line x1="550" y1="115" x2="567" y2="115" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>

  <!-- Feedback loop -->
  <rect x="200" y="180" width="400" height="140" rx="10" fill="#f8fafc" stroke="#94a3b8" stroke-width="1" stroke-dasharray="6,3"/>
  <text x="400" y="200" text-anchor="middle" font-size="12" fill="#475569" font-weight="600">Outcome Feedback</text>

  <rect x="220" y="215" width="100" height="40" rx="5" fill="#dcfce7" stroke="#16a34a" stroke-width="1"/>
  <text x="270" y="240" text-anchor="middle" font-size="10" fill="#14532d">MERGED</text>

  <rect x="340" y="215" width="100" height="40" rx="5" fill="#fef2f2" stroke="#dc2626" stroke-width="1"/>
  <text x="390" y="240" text-anchor="middle" font-size="10" fill="#991b1b">FAILED ×3</text>

  <rect x="460" y="215" width="120" height="40" rx="5" fill="#fef2f2" stroke="#dc2626" stroke-width="1"/>
  <text x="520" y="240" text-anchor="middle" font-size="10" fill="#991b1b">REGRESSION</text>

  <text x="270" y="280" text-anchor="middle" font-size="10" fill="#166534">reset failure count</text>
  <text x="390" y="280" text-anchor="middle" font-size="10" fill="#991b1b">suppress category</text>
  <text x="520" y="280" text-anchor="middle" font-size="10" fill="#991b1b">pause + rollback</text>

  <!-- Feedback arrow back to top -->
  <path d="M600,250 L740,250 L740,35 L695,35" fill="none" stroke="#64748b" stroke-width="1.5" stroke-dasharray="4,3" marker-end="url(#arr2)"/>
  <text x="750" y="150" text-anchor="start" font-size="10" fill="#64748b" transform="rotate(90,750,150)">feeds back</text>
</svg>

`EvolutionTicker` owns this pipeline. Every invocation path — event-driven or timer — passes through the same gates. There is no shortcut that bypasses the circuit breaker or the conflict detector. This is enforced by architecture, not convention.

## The Safety Stack

The interesting part isn't the individual gates — circuit breakers and budget limits are well-understood patterns. The interesting part is how they compose to handle the specific risks of autonomous self-improvement.

**Data autophagy** is the central risk. A system that improves itself can degrade itself just as easily — one bad improvement lands, health drops slightly, the next improvement compensates by over-optimising in a different direction, and the system spirals into incoherence while every individual change looked reasonable. The `HealthScoreTracker` aggregates health across capability areas with configurable weights, and the `ImprovementCircuitBreaker` trips OPEN when the aggregate score drops below threshold or when the health delta turns sharply negative. Once OPEN, no improvements are proposed until sustained recovery is observed.

**Regression detection** handles the single-improvement case. When an improvement merges, `RegressionDetector` captures the current health snapshot as a baseline and monitors the system for a configurable window. If health degrades, a `ConfidenceScorer` evaluates how likely it is that this specific improvement caused the regression — checking whether the degradation correlates with the modified areas, whether other improvements merged in the same window (which reduces confidence), and whether the regression appears in areas unrelated to the change (which also reduces confidence). High confidence triggers an automatic rollback case. Medium confidence pauses the category. Low confidence emits a signal for the next tick to consider.

The confidence scoring is composable:

```java
double confidence = 0.0;
if (regressionWithinWindow(before, after))  confidence += 0.2;
if (multipleAreasDegraded(before, after))   confidence += 0.1;
// Each signal adds or subtracts — clamped to [0, 1]
```

Simple additive weights. No ML, no black box. A developer reading the code can predict exactly what the scorer will do for any given health state.

**Anti-oscillation** prevents the improve-revert-re-propose cycle. `RollbackHistory` tracks what was recently rolled back. `ImprovementGoalFormationStrategy` checks this history before proposing — if the same category and target were reverted within the regression window, the proposal is suppressed. The system won't keep trying the same thing that already failed.

**Conflict avoidance** serialises improvements that would touch overlapping files. Two dependency updates in the same module are serialised. A small lint fix (under the trivial threshold) can run concurrently with a large refactor in the same directory — it's exempt from directory-level conflict detection, only blocked by exact file overlap. This prevents merge conflicts that LLMs handle poorly.

## Growing from Nothing

The system doesn't need pre-programmed categories to start. The capability taxonomy is built around an SPI — `CapabilityArea` — with ten bootstrap areas covering stability, performance, execution, coordination, perception, autonomy, cognitive reasoning, cognitive memory, safety, and integration. Each area provides an `assess()` method that returns a health score and a landscape position: AHEAD, AT_PARITY, BEHIND, or ABSENT.

<svg viewBox="0 0 800 260" xmlns="http://www.w3.org/2000/svg" style="max-width:100%;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr3" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6" fill="#475569"/></marker>
  </defs>
  <!-- Capability areas -->
  <rect x="10" y="10" width="170" height="240" rx="8" fill="#f8fafc" stroke="#94a3b8" stroke-width="1.5"/>
  <text x="95" y="35" text-anchor="middle" font-size="13" fill="#334155" font-weight="600">Capability Areas</text>
  <text x="30" y="60" font-size="11" fill="#16a34a">● stability     0.8</text>
  <text x="30" y="80" font-size="11" fill="#16a34a">● performance   0.7</text>
  <text x="30" y="100" font-size="11" fill="#ca8a04">● coordination  0.5</text>
  <text x="30" y="120" font-size="11" fill="#dc2626">● autonomy      0.2</text>
  <text x="30" y="140" font-size="11" fill="#94a3b8">○ cog-memory    ABSENT</text>
  <text x="30" y="165" font-size="10" fill="#64748b" font-style="italic">assess() → health score</text>
  <text x="30" y="180" font-size="10" fill="#64748b" font-style="italic">         → landscape position</text>
  <text x="30" y="195" font-size="10" fill="#64748b" font-style="italic">         → impact / cost / ROI</text>

  <!-- Gap map -->
  <rect x="220" y="10" width="160" height="130" rx="8" fill="#fef2f2" stroke="#dc2626" stroke-width="1.5"/>
  <text x="300" y="35" text-anchor="middle" font-size="13" fill="#991b1b" font-weight="600">Gap Map</text>
  <text x="300" y="55" text-anchor="middle" font-size="11" fill="#b91c1c">sorted by ROI ↓</text>
  <text x="240" y="80" font-size="11" fill="#334155">1. autonomy       2.67</text>
  <text x="240" y="100" font-size="11" fill="#334155">2. cog-memory     ∞</text>
  <text x="240" y="120" font-size="11" fill="#334155">3. coordination   1.25</text>

  <!-- Research pipeline -->
  <rect x="420" y="10" width="170" height="130" rx="8" fill="#eff6ff" stroke="#2563eb" stroke-width="1.5"/>
  <text x="505" y="35" text-anchor="middle" font-size="13" fill="#1e40af" font-weight="600">Research Pipeline</text>
  <text x="440" y="60" font-size="11" fill="#1d4ed8">Scoper →</text>
  <text x="440" y="80" font-size="11" fill="#1d4ed8">  Searcher →</text>
  <text x="440" y="100" font-size="11" fill="#1d4ed8">    Analyser →</text>
  <text x="440" y="120" font-size="11" fill="#1d4ed8">      Hypothesis Former</text>

  <!-- Hypotheses -->
  <rect x="630" y="10" width="150" height="130" rx="8" fill="#dcfce7" stroke="#16a34a" stroke-width="1.5"/>
  <text x="705" y="35" text-anchor="middle" font-size="13" fill="#14532d" font-weight="600">Hypotheses</text>
  <text x="650" y="60" font-size="11" fill="#166534">technique: X</text>
  <text x="650" y="80" font-size="11" fill="#166534">target: autonomy</text>
  <text x="650" y="100" font-size="11" fill="#166534">radar: TRIAL</text>
  <text x="650" y="120" font-size="11" fill="#166534">→ signal deposit</text>

  <!-- Arrows -->
  <line x1="180" y1="75" x2="217" y2="75" stroke="#475569" stroke-width="1.5" marker-end="url(#arr3)"/>
  <line x1="380" y1="75" x2="417" y2="75" stroke="#475569" stroke-width="1.5" marker-end="url(#arr3)"/>
  <line x1="590" y1="75" x2="627" y2="75" stroke="#475569" stroke-width="1.5" marker-end="url(#arr3)"/>

  <!-- Bottom: corpus -->
  <rect x="420" y="160" width="360" height="80" rx="8" fill="#f5f3ff" stroke="#7c3aed" stroke-width="1.5"/>
  <text x="600" y="185" text-anchor="middle" font-size="13" fill="#5b21b6" font-weight="600">Research Corpus (Living Systematic Review)</text>
  <text x="600" y="205" text-anchor="middle" font-size="11" fill="#6d28d9">findings persist across cycles</text>
  <text x="600" y="225" text-anchor="middle" font-size="11" fill="#6d28d9">Technology Radar tracks maturity</text>
  <line x1="505" y1="140" x2="505" y2="157" stroke="#7c3aed" stroke-width="1.5" marker-end="url(#arr3)"/>
</svg>

A `GapMap` computes ROI-ranked gaps from these assessments. ABSENT and BEHIND areas surface as the highest-priority gaps. The research pipeline — four SPIs following the PRISMA protocol methodology — investigates those gaps and produces improvement hypotheses. Those hypotheses are deposited as signals, entering the same consensus-based proposal path as every other improvement.

The practical consequence: you can point this system at an empty codebase with a briefing document, and the evolution loop will bootstrap from there. The briefing seeds the capability areas. Research discovers what "good" looks like. Hypotheses become proposals. The loop runs. No pre-programmed improvement scripts required.

## The Conductor, Not the Autopilot

The system can run autonomously. That's the capability. But the design intent is a command centre where a developer acts as conductor — watching every gate decision, every health score shift, every regression detection, and intervening at any point.

Every state transition emits an event: `CIRCUIT_BREAKER_TRIPPED`, `REGRESSION_DETECTED`, `IMPROVEMENT_CONFLICT_DETECTED`, `CAPABILITY_AREA_CHANGED`. The `ImprovementCategoryTracker` supports manual `pauseCategory` and `unpauseCategory`. The circuit breaker has `manualReset`. The budget enforcer has a structural deny list — safety-critical components like `EvolutionTicker`, `RegressionDetector`, and `ImprovementCircuitBreaker` cannot modify themselves. The improvement system cannot undermine its own safety constraints.

This is what makes the system practical rather than theoretical. Autonomous capability gives you the option to step back. Observable, interruptible execution gives you the confidence to actually do it. The conductor decides when to let the orchestra play and when to stop the music.

## What the Architecture Looks Like

Eleven issues. Sixty-two new files. Three modules touched (api, runtime-core, runtime). The layering is deliberate:

- **api module**: all SPIs and records — `CapabilityArea`, `ResearchCorpus`, `RollbackPolicy`, `HealthPolicy`, the research pipeline interfaces. Pure contracts, no behaviour.
- **runtime-core**: all implementations — `EvolutionTicker`, `ImprovementCircuitBreaker`, `RegressionDetector`, `ConflictDetector`, `HealthScoreTracker`, `ImprovementCategoryTracker`, `ResearchPipelineOrchestrator`, default SPI implementations.
- **runtime**: the rollback case template YAML — a shortened improvement lifecycle (confirm regression → revert → submit PR → fast-track review → integrate → record outcome).

Everything in runtime-core uses direct instantiation in tests — no Quarkus container, no CDI magic, no Testcontainers. The integration test creates the full pipeline by hand, wires the components together, and exercises the loop end-to-end. A test that needs a container to verify business logic is a test that's testing the wrong thing.

## What Comes Next

The engine provides the pipeline, the contracts, and rule-based defaults. The intelligence comes from three future layers:

The cognitive agent (blocks) wires `EvolutionTicker` into `CognitionCore` for personality-modulated improvement priorities — a curious agent researches more aggressively, a cautious one raises the circuit breaker threshold.

The research loop (blocks) provides LLM-powered implementations of the four research SPIs — real literature search, structured analysis using PRISMA protocol, and hypothesis formation that goes beyond pattern matching.

The cognitive memory (neocortex) replaces `InMemoryResearchCorpus` with a persistent store backed by the knowledge graph, where improvement outcomes become episodic memory and capability assessments inform long-term strategy.

The infrastructure is ready for all three. The SPIs are defined, the default implementations work, and the safety gates are tested. The next session can plug in the intelligence without redesigning the plumbing.
