# Adaptive Planning Intelligence — Research & Epic Design

**Date:** 2026-08-18
**Scope:** Classical planning, GOAP, adaptive/reactive planning — state of the art and application to casehub-engine
**Sources:** ICAPS 2024-2025, NeurIPS 2023-2025, AAAI 2026, EMNLP 2025, IEEE CoG, AIIDE, Google Scholar, production game AI systems

---

## 1. Where We Stand Today

The engine's planning infrastructure spans five techniques that no single production system in the literature combines:

1. **Utility-based goal selection** — `GoalBasedCompletion` with ordered `GoalKind` evaluation, priority-driven `GoalRevisionEvaluator`
2. **HTN decomposition** — `DecompositionStrategy` SPI with `CompoundTask`/`LeafTask` hierarchy, `LlmDecompositionStrategy`
3. **GOAP leaf planning** — `GoapPlanner` (A* forward search, preconditions, effects, soft preconditions, cost/benefit)
4. **CBR experience learning** — `CbrRetrievalService`, `ExperienceAnalyser`, `CbrCaseRetainObserver` recording execution traces
5. **Multi-agent coordination** — `ComposableAgentRoutingStrategy` with signal providers (workload, trust, experience, personality, semantic)

Each axis works. The gaps are in the *connections between them* and in the *adaptation intelligence* that decides what to do when plans go wrong.

### Specific Gaps

**GOAP is isolated from decomposition.** The `GoapPlanner` runs at dispatch time via `GoapPlanningStrategy` — it selects the next action to execute from eligible bindings. It is not wired as a `DecompositionStrategy`, meaning it cannot produce a full plan upfront. The LLM decomposes; GOAP dispatches. There is no fallback if the LLM is unavailable, slow, or produces a poor plan.

**Adaptation is LLM-only.** `ForwardReplanRevision` is the sole `PlanRevisionStrategy`. It re-invokes the LLM with completed step history and current context. There is no classical planning fallback, no graduated response (small perturbation vs. fundamental plan failure), and no cost-benefit evaluation before spending tokens on an LLM call.

**Triggers are binary.** `EveryStepTrigger` replans after every completion (expensive, often unnecessary). `OnFailureTrigger` replans only on failure (misses cases where context has drifted enough to invalidate the remaining plan even though no step has failed). There is no middle ground.

**Failure handling is undifferentiated.** Worker outcomes (Declined, Failed, Expired) all route through the same `OutcomePolicy` path. A transient network timeout gets the same treatment as a fundamental capability mismatch. Rerouted agents receive no diagnosis of why the previous agent failed — they start from scratch.

**No expectation tracking.** The engine reacts to events (completion, failure, context change) but does not proactively monitor whether the plan is on track. Declared effects in GOAP actions and `@Effect` annotations are not validated against actual context changes. State divergence goes undetected until a downstream step fails.

**No meta-reasoning.** There is no formalized decision about whether to continue executing the current plan, adapt it, or abandon the goal entirely. The generation counter and concurrency semaphore prevent redundant adaptation, but they don't answer the question "is this adaptation worth the cost?"

---

## 2. What the Literature Tells Us

### 2.1 Classical Planning — The Hybrid Consensus

The dominant trend in ICAPS 2024-2025 is **hybrid neuro-symbolic planning**: LLMs for domain knowledge, heuristic generation, and high-level decomposition; classical engines for execution correctness, search guarantees, and formal properties.

**LLMs as heuristic generators, not direct planners** (Correa, Pereira & Seipp, NeurIPS 2025). LLMs generate Python heuristic functions evaluated within greedy best-first search. This dramatically outperforms domain-independent heuristics on unseen tasks. The key insight: LLMs are good at encoding domain knowledge but poor at systematic search. Use them to *inform* a classical planner, not *replace* it.

This validates our architecture — `LlmDecompositionStrategy` produces plans consumed by the `DagDriver` execution engine. But it also suggests we should wire GOAP as a decomposition strategy so the classical planner can operate independently when the LLM is unavailable or when the domain is well-specified enough that A* search is sufficient.

**Decoupled search** (Speck & Gnad, ICAPS 2024 Best Paper) transforms planning tasks so independent plan components are solved separately. This maps directly to our DAG driver — parallel binding dispatch already exploits causal independence. The research confirms our structural approach is sound.

**Portfolio planners** sequence planners of increasing generality with time allocation — easy cases handled quickly by fast heuristics, harder ones by slower but more capable planners. This suggests a `PortfolioDecompositionStrategy` that tries GOAP first (milliseconds, deterministic) and escalates to LLM (seconds, probabilistic) only when needed.

### 2.2 GOAP — Evolution Since Orkin

The core GOAP algorithm (A* over world-state graphs with preconditions/effects) is largely unchanged since F.E.A.R. (2005). What has evolved is the surrounding architecture:

**Goal selection is decoupled from plan search.** GOAP handles "how to achieve"; a separate system (utility, blackboard, or LLM) handles "what to achieve." We already do this — `GoalBasedCompletion` selects goals, `GoapPlanningStrategy` plans to achieve them.

**GOAP + HTN hybrids are the strongest research thread.** Vázquez-Salceda et al. (2024, IEEE Transactions on Games) and Ontañón (2023) formalize the pattern: HTN's hierarchical decomposition provides designer control and search speed; GOAP provides flexibility within leaf tasks. High Moon Studios' migration from GOAP to HTN for Transformers: Fall of Cybertron (2012) showed HTN yielded longer plans faster but lost GOAP's adaptability — the hybrid recovers both. Our `DecompositionStrategy` (HTN) + `GoapPlanningStrategy` (GOAP leaf dispatch) is exactly this hybrid.

**Multi-agent GOAP** remains largely unsolved in game AI. Approaches include resource reservation, hierarchical squad planning, and dynamic cost adjustment. Our signal-based routing (`ComposableAgentRoutingStrategy`) addresses coordination differently — via scoring rather than shared-state planning — which is more appropriate for an orchestration engine where agents are external workers.

**Utility-based GOAP** is the consensus for goal selection. Utility functions score goals; GOAP plans to achieve the selected goal. The UDGOAP thesis extends this to simultaneous multi-goal planning with partial satisfaction. Our `GoalBasedCompletion` with ordered `GoalKind` evaluation is in this family.

**Nobody has combined all five:** utility goal selection + HTN decomposition + GOAP leaf planning + CBR learning + multi-agent coordination. We do. The value is in deepening each axis and connecting them, not in adding a sixth technique.

### 2.3 Adaptive Planning — The Critical Gap

This is where the richest research exists and where we have the most to gain.

#### Plan Repair vs. Replanning

The literature converges on **local-repair-first with escalation to replanning** based on divergence measurement:

- **KER 2025** (Optimally Stable Plan Repair) quantifies the tradeoff: as divergence increases, repair mechanisms attempt to reuse actions that lose relevance. A divergence threshold should trigger full replanning.
- **ICAPS 2025** HTN repair comparison (SHOPFIXER, IPYHOPPER, REWRITE) confirms repair outperforms replanning for small perturbations; replanning wins when divergence is high.
- **H-RePlan (2026)** formalizes repair primitives: Rebind, InsertPrereq, Substitute, Rewire, Bypass — escalating when local repair is insufficient.
- **Plan grafting** (Bansod et al., 2023) splices repaired subplans into the original, preserving completed work.

The practical rule: repair when failure is local (one agent, few downstream dependents in the DAG). Replan when the failure invalidates preconditions shared by multiple downstream steps.

Our `ForwardReplanRevision` is always a full replan. We should separate plan repair (restore validity after a local failure) from plan optimization (improve quality given new context), because they require different algorithms, different triggers, and different cost profiles.

#### The Persist / Refine / Concede Trichotomy

**MPDF** (Yang & Thomason, AAAI 2026) formalizes three meta-cognitive actions that map directly to our adaptation decisions:

- **Persist** — the current plan is still valid; continue executing. Current trigger: implicit (no trigger fires, execution continues).
- **Refine** — the plan needs adjustment but the goal is still achievable. Current mechanism: `ForwardReplanRevision`. Gap: no graduated refinement; every adaptation is a full LLM re-invocation.
- **Concede** — the goal is not achievable; abandon and potentially pivot. Current mechanism: `GoalAbandonmentEvaluator` counts failures against a threshold, but it operates on a per-agent basis, not per-plan.

The gap is that we lack a formalized decision point that evaluates which of these three actions is appropriate *before* invoking adaptation. The generation counter prevents *redundant* adaptation but doesn't prevent *wasteful* adaptation.

**SOFAI-LM** (IBM, AAAI 2026) wraps a fast system with a metacognitive controller that invokes slow reasoning only when cost-benefit analysis justifies it. Applied to our system: evaluate whether the expected improvement from LLM adaptation justifies the latency and token cost, given the remaining plan value.

#### Progress-Gated Adaptation

**ReflexGrad (2025)** introduces progress-gated dual-process routing: tactical refinement first, escalating to strategic replanning when forward progress stalls. This is the middle ground between `EveryStepTrigger` and `OnFailureTrigger`.

**AdaPlanner** (Sun et al., NeurIPS 2023) distinguishes in-plan refinement (minor observation mismatch) from out-of-plan refinement (major state failure requiring full regeneration). Failed coarse steps get decomposed finer, not just retried — a pattern directly applicable to our HTN system.

**SIPS** (Zhi-Xuan et al., NeurIPS 2020) models boundedly-rational planners that replan only on "surprise" — a quantifiable measure of state divergence from expectation. This requires expectation tracking, which we lack.

#### Failure Classification

**TART (2026)** derives actionable failure taxonomies from multi-agent executions:
- **Transient failures** — retry with the same or a different agent (network timeout, resource contention)
- **Knowledge failures** — the agent lacked information or capability; replanning is needed to try a different approach
- **Infeasible goals** — the objective cannot be achieved regardless of agent or approach; abandon

Our current system treats all non-success outcomes through `OutcomePolicy`, which differentiates REROUTE vs FAULT but does not classify *why* the failure happened. A transient timeout gets rerouted the same way a fundamental capability mismatch does.

**Epistemic Miscalibration (2026)** adds a crucial distinction: execution errors (the agent tried the right thing but failed mechanically) vs. knowledge errors (the agent tried the wrong thing because the plan was based on incorrect assumptions). The latter requires replanning; the former just needs retry.

#### Reflexion and Failure Context

**Reflexion** (Shinn et al., NeurIPS 2023) demonstrated that verbal self-reflection stored in episodic memory before retry dramatically improves subsequent attempts. The key insight: verbal critique outperforms scalar reward for plan revision.

Applied to our system: when a worker fails and gets rerouted, the replacement agent receives no context about *why* the previous attempt failed. The `_diagnostics.<bindingName>` state tracks `excludedAgents` and bare `reason` strings, but there is no structured failure analysis. Feeding "why did this fail?" into the rerouted agent's context — and into `ForwardReplanRevision`'s prompt — would improve both individual retry success rates and plan adaptation quality.

#### Contingent Planning

**HQCP (2025)** extends HTN planning to partial observability. The practical insight: when failure modes are predictable (and in an agent system they often are — timeout, decline, capability gap), pre-computing alternative branches at decomposition time is cheaper than reactive replanning.

Our `JoinType.ANY_OF` already supports disjunctive joins in the DAG. Extending `DagNode` with conditional edges ("if this node fails, activate that alternative") would enable contingent plans without fundamental architectural change.

---

## 3. The Epic: Adaptive Planning Intelligence

### Design Principles

1. **Classical first, LLM when needed.** Every adaptation mechanism should have a fast classical path and an LLM-powered path. The classical path handles well-defined domains; the LLM path handles ambiguous or novel situations.
2. **Graduate the response.** Small perturbations get local repair. Context drift gets plan refinement. Fundamental failure gets full replanning or goal abandonment. The system should never use the most expensive response when a cheaper one suffices.
3. **Connect the axes.** CBR traces should inform GOAP costs. GOAP should be available as a decomposition strategy. Failure diagnosis should feed into adaptation prompts. The five techniques we already have should reinforce each other.
4. **Measure before acting.** Every adaptation should be preceded by a measurement: how far has the state diverged? How much has the plan degraded? Is the remaining plan value worth the adaptation cost?

### Issue Breakdown

---

#### Issue 1: Plan Monitoring and Expectation Tracking

**What:** Validate that actual context changes match declared effects. Detect state divergence between what the plan expected and what actually happened.

**Why this matters:** Every adaptation technique in the literature requires a measurement of "how wrong is the current state?" Without expectation tracking, the engine can only react to discrete events (completion, failure) — it cannot detect *silent* plan degradation where steps succeed but produce unexpected results, or where external context changes invalidate future steps' preconditions.

Consider a case where Worker A succeeds but produces output that doesn't satisfy Worker B's preconditions. Without expectation tracking, Worker B gets dispatched, fails, gets rerouted, fails again — burning retries on a problem that could have been detected immediately after Worker A completed.

**How it works:**
- GOAP actions already declare preconditions and effects. The `@Effect` annotation and type inference in the annotations module map return types to context keys. This is all build-time metadata that is currently unused at runtime.
- After a worker completes, compare the actual context changes against the declared effects of the corresponding GOAP action. If the expected effects are not present (or unexpected effects appear), fire a new `ExpectationViolatedTrigger`.
- Track cumulative divergence as a score. Individual violations may be tolerable; accumulated divergence signals plan invalidity.

**What it enables:** Progress-gated triggers (#4), meta-reasoning (#7), and contingent planning (#11) all depend on being able to measure plan health. This is the foundation.

**Scale:** M | **Complexity:** Med | **Blocks:** #4, #7

---

#### Issue 2: GOAP as a Decomposition Strategy

**What:** Wire the existing `GoapPlanner` as a `DecompositionStrategy` (id=`"goap"`) that produces a full `DagPlan<LeafTask>` from A* search over the precondition/effect graph. Enhance the planner with backward pruning, forward simulation, ternary world state, and dynamic cost computation.

**Why this matters:** The `GoapPlanner` currently operates only at dispatch time — it selects the next single action from eligible bindings. It cannot produce a complete plan upfront because it is not connected to the `DecompositionStrategy` SPI. This means:
- When `LlmDecompositionStrategy` is unavailable (no `ChatModelProvider`), there is no decomposition at all — cases fall back to choreography.
- When the domain is well-specified (all preconditions and effects are declared), the LLM is unnecessary overhead — a classical A* search would produce the same plan in milliseconds instead of seconds.
- Plan adaptation via `ForwardReplanRevision` has no classical fallback. If the LLM produces a poor revision, there is no "ground truth" to compare against.

**How it works:**
- `GoapDecompositionStrategy` implements `DecompositionStrategy<JsonNode>`. It builds `GoapAction` instances from bindings with declared capabilities, evaluates the goal conditions from `CaseDefinition.goalToEffectKeys`, runs `GoapPlanner.plan()`, and maps the resulting action sequence to a `DagPlan<LeafTask<JsonNode>>`.
- Uses the same `GoapAction` construction as `GoapPlanningStrategy` — preconditions from method parameters (annotations) or explicit declarations, effects from return types or `@Effect`.
- Returns `Uni.createFrom().item(plan)` — synchronous, no LLM call.
- **Backward pruning:** Before A* runs, work backward from the goal and remove actions that cannot contribute to reaching it. Shrinks the branching factor for case definitions with many bindings.
- **Forward simulation:** After finding a plan, simulate execution forward and strip actions whose effects are already satisfied by earlier actions. Each redundant action removed saves a real worker execution.
- **Ternary world state:** Extend `GoapWorldState` to support TRUE/FALSE/UNKNOWN. When a condition is UNKNOWN, generate plans for both values; only evaluate the condition at runtime if the plans differ. Enables planning under partial observability without full contingent planning.
- **Dynamic cost computation:** `GoapAction` gains an optional `CostFunction` evaluated against the current case context at planning time. In the annotations module: `@Cost`-annotated methods. In YAML: JQ expressions. Static costs remain the default when no dynamic cost is declared. Issue #10 (Learned Costs from CBR) layers on top as a reliability adjustment factor.

**What it enables:** Portfolio strategy (#6), dynamic decomposition depth (#9), learned action costs (#10), and contingent planning (#11) all build on GOAP being available as a decomposition strategy.

**Scale:** M | **Complexity:** Med | **Blocks:** #6, #9, #10, #11

---

#### Issue 3: Failure Taxonomy and Diagnosis Routing

**What:** Classify worker failures into categories that determine the correct response. Thread failure diagnosis to rerouted agents.

**Why this matters:** The engine currently treats all non-success outcomes through the same `OutcomePolicy` path. A worker that times out due to a transient network issue gets the same reroute treatment as a worker that declines because it fundamentally lacks the capability. This wastes retries on problems that need replanning and delays replanning for problems that need it immediately.

The TART (2026) taxonomy identifies three failure categories, each with a different optimal response:

| Category | Signal | Correct response | Current behaviour |
|----------|--------|-------------------|-------------------|
| **Transient** | Timeout, 5xx, resource contention | Retry same agent or reroute to equivalent | Reroute (correct but imprecise — excludes the agent permanently) |
| **Knowledge** | Decline with reason, wrong output schema, precondition mismatch | Replan — the approach is wrong, not the agent | Reroute (wrong — a different agent with the same approach will also fail) |
| **Infeasible** | Repeated failures across all candidates, goal contradiction | Abandon goal, escalate | Reroutes exhausted → FAULTED (correct but slow — burns all retries first) |

The second gap is diagnosis threading. When Worker A fails and Worker B is rerouted to handle the same task, Worker B receives no context about why Worker A failed. Empirically (COOP2, 2026), passing failure diagnosis to rerouted agents significantly improves their success rate over blind rerouting.

**How it works:**
- `FailureCategory` sealed type: `Transient(reason)`, `Knowledge(reason, missingContext)`, `Infeasible(reason)`.
- `FailureClassifier` SPI with a default implementation that classifies based on `WorkerOutcome` type, reason text patterns, and retry history. `Expired` → `Transient` by default. `Declined` → `Knowledge` (the agent explicitly said it can't do this). `Failed` → heuristic classification based on reason.
- `OutcomePolicy` gains category-aware routing: `Transient` → retry without excluding the agent (or reroute with soft exclusion). `Knowledge` → trigger plan adaptation. `Infeasible` → abandon via `GoalAbandonmentEvaluator`.
- `_diagnostics.<bindingName>` enriched with structured `FailureDiagnosis` (category, reason, context snapshot at failure time). Rerouted agents receive this via `WorkerContext`.

**What it enables:** Reflexion-style critique (#5), meta-reasoning (#7), and dynamic decomposition depth (#9) all depend on knowing *why* a failure happened.

**Scale:** M | **Complexity:** High | **Blocks:** #5, #7, #9

---

#### Issue 4: Progress-Gated Adaptation Trigger

**What:** A `ProgressGatedTrigger` (id=`"progress"`) that fires adaptation when measured plan quality degradation exceeds a configurable threshold — the middle ground between every-step and on-failure.

**Why this matters:** `EveryStepTrigger` invokes LLM adaptation after every worker completion. For a 10-step plan, that is 10 LLM calls even when the plan is executing perfectly. At typical LLM latencies (2-5 seconds) and token costs, this adds 20-50 seconds of overhead and significant cost to every case.

`OnFailureTrigger` only fires on worker failure. This misses a critical scenario: context drift. External signals, sub-case completions, or worker outputs can change the case context in ways that invalidate future plan steps without any step actually failing. The remaining plan becomes stale, but no trigger fires until a downstream step fails — by which point multiple steps may have executed unnecessarily.

The research (ReflexGrad 2025, SIPS 2020) converges on measuring "surprise" — the divergence between expected state and actual state — as the trigger criterion. This requires the expectation tracking from Issue #1.

**How it works:**
- After each worker completion, `ProgressGatedTrigger` evaluates the divergence score from the expectation tracker (#1).
- If divergence exceeds a configurable threshold (e.g., >30% of remaining preconditions invalidated), fires `PROCEED`. Otherwise, fires `SKIP`.
- The threshold is configurable per case definition via `AdaptationConfig`: `adaptation: {trigger: progress, threshold: 0.3}`.
- Falls back gracefully when expectation tracking is unavailable (no GOAP annotations) — degrades to `OnFailureTrigger` behaviour.

**Why this ordering:** Depends on #1 (expectation tracking provides the divergence measurement).

**Scale:** S | **Complexity:** Med | **Depends on:** #1

---

#### Issue 5: Reflexion-Style Failure Critique

**What:** Before replanning or rerouting, capture a structured "why did this fail?" analysis. Feed the critique into `ForwardReplanRevision` prompts and rerouted agent contexts.

**Why this matters:** Reflexion (Shinn et al., NeurIPS 2023) is one of the strongest empirical results in LLM agent adaptation. Verbal self-reflection stored in episodic memory before retry dramatically outperforms scalar reward for plan revision. The key finding: a natural-language critique of "what went wrong and what should change" produces better subsequent plans than simply reporting "step 3 failed."

Our current failure state in `_diagnostics.<bindingName>` stores:
- `status` (DECLINED, FAILED, EXPIRED)
- `attempts` count
- `history[]` with bare entries
- `excludedAgents[]`

This is telemetry, not diagnosis. A rerouted agent or the `ForwardReplanRevision` strategy sees *that* something failed but not *why* in a way that informs the next attempt.

**How it works:**
- On worker failure, `FailureClassifier` (#3) produces a `FailureDiagnosis` with category and structured reason.
- For `Knowledge` failures, an optional LLM critique step (gated by `ChatModelProvider` availability) generates a one-sentence verbal analysis: "The agent attempted to resolve the entity via exact match but the input contained fuzzy identifiers requiring similarity search."
- The critique is stored in `_diagnostics.<bindingName>.critique` and appended to the `ForwardReplanRevision` prompt context.
- Rerouted agents receive the critique via `WorkerContext` so they can avoid the same approach.
- Classical fallback when LLM unavailable: the `FailureDiagnosis` reason string is used directly (no verbal enrichment).

**Why this ordering:** Depends on #3 (failure taxonomy provides the structured diagnosis to critique).

**Scale:** S | **Complexity:** Med | **Depends on:** #3

---

#### Issue 6: Portfolio Decomposition Strategy

**What:** `PortfolioDecompositionStrategy` (id=`"portfolio"`) — a cascading strategy that tries fast classical decomposition first and escalates to LLM only when needed.

**Why this matters:** Portfolio planners (CP4TP, IPC competition results) consistently outperform single-planner approaches by matching problem difficulty to planner capability. Easy problems are solved instantly by fast heuristics; hard problems get the full treatment.

In our system, many case definitions have well-specified preconditions and effects (especially those using `@Effect` annotations and GOAP mode). For these, the `GoapPlanner` can produce a valid plan in milliseconds. Invoking `LlmDecompositionStrategy` adds 2-5 seconds of latency and token cost for the same result.

The portfolio pattern also provides resilience: if the LLM is unavailable, slow, or returns an invalid plan, the classical fallback ensures cases still get decomposed.

**How it works:**
- `PortfolioDecompositionStrategy` takes an ordered list of delegate strategies (default: `["goap", "llm"]`).
- Tries each strategy in order with a configurable time budget per strategy (default: 500ms for GOAP, 30s for LLM).
- First strategy that returns a non-empty valid plan wins.
- GOAP failure (no plan found — unsatisfiable preconditions or missing actions) is not an error; it escalates to the next strategy.
- YAML: `decompositionStrategy: portfolio` or explicit `decompositionStrategy: {portfolio: [goap, llm]}`.

**Why this ordering:** Depends on #2 (GOAP must be available as a decomposition strategy for the cascade to include it).

**Scale:** M | **Complexity:** Med | **Depends on:** #2

---

#### Issue 7: Persist / Refine / Concede Meta-Reasoning

**What:** Formalize the adaptation decision as a first-class sealed type with cost-benefit evaluation before invoking adaptation.

**Why this matters:** This is the architectural centrepiece of the epic. Every other issue provides a capability (monitoring, classification, decomposition); this issue provides the *decision framework* that determines which capability to use and when.

Without meta-reasoning, the adaptation pipeline is reactive: a trigger fires, a revision strategy runs, the result is applied. There is no evaluation of whether adaptation is *worth doing*. Consider:
- A compound with 8 of 10 steps completed. Step 9 fails with a transient error. The `EveryStepTrigger` fires `ForwardReplanRevision`, which invokes the LLM to produce a new plan — but the optimal action is simply to retry step 9.
- A compound with 2 of 10 steps completed. Both produced unexpected output. The plan is fundamentally wrong, but `OnFailureTrigger` hasn't fired because no step has technically "failed."
- A compound has been adapted 4 times already, each time producing a plan that fails at the next step. The cumulative LLM cost exceeds the value of completing the case. The optimal action is to concede — abandon the goal and escalate.

MPDF (AAAI 2026) formalizes the trichotomy:
- **Persist** — the plan is still valid; continue executing without adaptation.
- **Refine** — the plan needs adjustment; invoke adaptation with scope proportional to the divergence.
- **Concede** — the goal is not achievable at acceptable cost; abandon and potentially pivot.

**How it works:**
- `AdaptationDecision` sealed type: `Persist(reason)` | `Refine(scope, estimatedCost)` | `Concede(reason, failedGoal)`.
- `AdaptationMetaReasoner` SPI evaluates the decision before adaptation runs. Inputs: divergence score (#1), failure category (#3), cumulative adaptation cost (tracked per compound), remaining plan value (steps remaining × estimated step value).
- `Refine.scope` ranges from `LOCAL` (repair one step) to `COMPOUND` (re-decompose the entire compound). Scope determines which revision strategy is invoked — local repair uses GOAP (#2), compound revision uses LLM.
- `Concede` integrates with `GoalAbandonmentEvaluator` — but triggered by cost-benefit analysis, not just failure count.
- Cumulative cost tracking: each adaptation records its cost (LLM tokens, wall-clock time, number of adaptations). Configurable cost ceiling per compound (`casehub.engine.adaptation.max-cost`).

**Why this ordering:** Depends on #1 (divergence measurement), #3 (failure classification), and #4 (progress-gated trigger provides the measurement inputs). This is Phase C because it synthesizes Phase A and B capabilities.

**Scale:** L | **Complexity:** High | **Depends on:** #1, #3, #4

---

#### Issue 8: Plan Repair vs. Plan Optimization Separation

**What:** Separate `RepairStrategy` (restore plan validity after failure) from `OptimizationStrategy` (improve plan quality given new context).

**Why this matters:** The IJCAI 2024 Plan Optimization Survey draws a clear distinction:
- **Plan repair** restores a broken plan to a valid state. Triggered by failure. The goal is *any* valid plan, quickly.
- **Plan optimization** improves a working plan's quality. Triggered by context change. The goal is a *better* plan, accepting higher cost.

Our `ForwardReplanRevision` conflates both — it always invokes the LLM with the full context and asks for a new plan. This means a simple retry-worthy failure triggers the same expensive process as a strategic context shift.

**How it works:**
- `RepairStrategy extends NamedStrategy` — restores plan validity. Built-in: `GoapRepairStrategy` (uses A* to find a local fix), `LlmRepairStrategy` (asks the LLM to fix the specific failure).
- `OptimizationStrategy extends NamedStrategy` — improves plan quality. Built-in: `ForwardReplanRevision` (existing, renamed).
- `AdaptationMetaReasoner` (#7) selects between repair and optimization based on `Refine.scope`:
  - `LOCAL` scope → `RepairStrategy` (fast, classical-first)
  - `COMPOUND` scope → `OptimizationStrategy` (full re-plan)
- Different triggers: repair fires on failure events; optimization fires on context change events when divergence exceeds the progress-gate threshold (#4).

**Why this ordering:** Depends on #7 (meta-reasoning determines which strategy type to invoke).

**Scale:** S | **Complexity:** Med | **Depends on:** #7

---

#### Issue 9: Dynamic Decomposition Depth (ADaPT Pattern)

**What:** When a leaf task fails due to a knowledge failure, promote it to a compound task and decompose it finer — instead of retrying at the same granularity.

**Why this matters:** ADaPT (Prasad et al., NAACL 2024) demonstrated that decomposing failed coarse steps into finer sub-steps outperforms retrying at the same level. The intuition: if "Analyse the transaction" fails, breaking it into "Extract transaction metadata," "Identify counterparties," "Evaluate risk indicators" gives each sub-step a better chance of succeeding because the scope is narrower and the capability matching is more precise.

This is hierarchical refinement — a core HTN concept that our system architecturally supports (compounds contain compounds) but does not currently use as a failure recovery mechanism. When a leaf task fails today, it gets rerouted to a different agent or faulted. It never gets decomposed into sub-tasks.

**How it works:**
- On `Knowledge` failure (from #3), the `AdaptationMetaReasoner` (#7) can select a `DECOMPOSE_DEEPER` scope.
- The failed `LeafTask` is promoted to a `CompoundTask` via `CasePlanModel.promoteToCompound()`.
- The `DecompositionStrategy` is re-invoked with the failed step's description as the new goal and a narrower set of available capabilities.
- Configurable max depth (`casehub.engine.adaptation.max-decomposition-depth`, default 3) prevents infinite refinement.
- Only `Knowledge` failures trigger deeper decomposition. `Transient` failures retry at the same level. `Infeasible` failures abandon.

**Why this ordering:** Depends on #2 (GOAP decomposition for classical refinement), #3 (failure taxonomy to gate on Knowledge failures).

**Scale:** M | **Complexity:** High | **Depends on:** #2, #3

---

#### Issue 10: Learned Action Costs from CBR Traces

**What:** Bridge CBR execution traces to GOAP action costs, so the planner's cost model reflects actual observed performance.

**Why this matters:** GOAP actions have a `cost` field, but it is statically declared. A worker that historically takes 30 seconds and fails 40% of the time has a much higher effective cost than one that takes 2 seconds with 95% success — but the planner treats them identically unless the developer manually tunes costs.

The CBR infrastructure already records everything needed: `ExperiencePlanStep` has duration, outcome, and worker identity. `ExperienceAnalyser.workerSuccessRates()` computes per-worker success rates. The missing piece is feeding this back into GOAP's cost model.

This closes the learning loop: cases execute → traces are retained in CBR → costs are updated → future plans prefer cheaper/more reliable paths → better cases execute. No other production system in the literature has this closed loop between CBR and GOAP.

**How it works:**
- `CbrCostProvider` computes empirical cost from `ExperiencePlanStep` records: `effectiveCost = avgDuration × (1 / successRate)`. High duration or low success rate inflates cost.
- `GoapDecompositionStrategy` (#2) queries `CbrCostProvider` for learned costs before planning. Uses learned costs when available (sufficient sample size), falls back to declared costs.
- Online updates: `CbrCaseRetainObserver` already fires on case terminal state. A new observer updates the cost cache.
- Configurable minimum sample size before learned costs override declared costs (`casehub.engine.goap.min-cost-samples`, default 10).
- Cold start: declared costs are used until sufficient traces accumulate. No degradation for new deployments.

**Why this ordering:** Depends on #2 (GOAP decomposition must exist for costs to feed into it). Phase C because it requires execution history to accumulate.

**Scale:** M | **Complexity:** High | **Depends on:** #2

---

#### Issue 11: Contingent Planning Branches

**What:** Pre-compute alternative branches at decomposition time for predictable failure modes, using conditional edges in the DAG.

**Why this matters:** Every other adaptation mechanism in this epic is *reactive* — it fires after a failure happens. Contingent planning is *proactive* — it pre-computes alternatives at decomposition time so that when a predictable failure occurs, the alternative path is already ready.

In an agent system, certain failure modes are highly predictable. External APIs time out. Agents decline tasks outside their capability. Data quality issues cause repeated failures on specific input types. When these patterns are known (from CBR history or domain knowledge), pre-computing fallback branches eliminates the latency of reactive replanning.

HQCP (2025) extends HTN planning to partial observability with plan quality optimization. The practical insight: pre-computed contingencies are cheaper than reactive replanning when failure probability is above ~15% (the crossover point depends on replanning latency vs. branch storage cost).

**How it works:**
- `DagNode` gains optional `contingency: DagPlan<LeafTask>` — an alternative sub-plan activated when the primary node fails.
- `DagDriver` evaluates contingencies before escalating to reactive adaptation: if a failed node has a contingency, activate it instead of marking the node FAILED.
- `GoapDecompositionStrategy` (#2) can generate contingencies when CBR history (#10) shows a failure rate above a configurable threshold for specific capabilities.
- `LlmDecompositionStrategy` can generate contingencies when prompted with known failure modes.
- `JoinType.ANY_OF` already handles disjunctive joins — contingencies reuse this mechanism.
- YAML: `contingency:` block on binding definitions for manually authored fallbacks.

**Why this ordering:** Depends on #2 (GOAP decomposition) and #9 (dynamic decomposition depth — contingencies are a complementary mechanism). Phase D because it is an architectural refinement that builds on all prior phases.

**Scale:** L | **Complexity:** High | **Depends on:** #2, #9

---

## 4. Implementation Phases

### Phase A — Foundations (parallelizable)

Issues #1, #2, #3 are independent and can be worked in parallel. They provide the three capabilities that everything else builds on:
- **Measurement** (#1) — knowing how far the plan has diverged
- **Classical planning** (#2) — fast deterministic decomposition
- **Classification** (#3) — knowing what kind of failure occurred

### Phase B — Enrichment (each builds on one Phase A item)

Issues #4, #5, #6 each extend one Phase A capability:
- **Progress trigger** (#4) ← Measurement (#1)
- **Reflexion critique** (#5) ← Classification (#3)
- **Portfolio strategy** (#6) ← Classical planning (#2)

### Phase C — Intelligence (the adaptive core)

Issues #7, #9, #10 synthesize prior capabilities:
- **Meta-reasoning** (#7) ← Measurement + Classification + Progress trigger
- **Dynamic depth** (#9) ← Classical planning + Classification
- **Learned costs** (#10) ← Classical planning + CBR traces

### Phase D — Architectural Refinements

Issues #8, #11 are refinements that assume the core is in place:
- **Repair/optimize separation** (#8) ← Meta-reasoning
- **Contingent planning** (#11) ← Classical planning + Dynamic depth

### Dependency Graph

```
Phase A (foundations, parallel):
  [1] Plan Monitoring
  [2] GOAP Decomposition
  [3] Failure Taxonomy

Phase B (enrichment):
  [1] ──→ [4] Progress-Gated Trigger
  [3] ──→ [5] Reflexion Critique
  [2] ──→ [6] Portfolio Strategy

Phase C (intelligence):
  [1] + [3] + [4] ──→ [7] Meta-Reasoning
  [2] + [3]        ──→ [9] Dynamic Depth
  [2]              ──→ [10] Learned Costs

Phase D (refinements):
  [7]      ──→ [8] Repair vs Optimize
  [2] + [9] ──→ [11] Contingent Planning
```

---

## 5. Summary Table

| # | Title | Scale | Complexity | Phase | Depends on | Key benefit |
|---|-------|-------|------------|-------|------------|-------------|
| 1 | Plan monitoring and expectation tracking | M | Med | A | — | Detect silent plan degradation; measure divergence |
| 2 | GOAP as DecompositionStrategy | M | Med | A | — | Fast classical decomposition; LLM fallback resilience |
| 3 | Failure taxonomy and diagnosis routing | M | High | A | — | Right response for each failure type; diagnosis threading |
| 4 | Progress-gated adaptation trigger | S | Med | B | 1 | Replan only when divergence warrants it |
| 5 | Reflexion-style failure critique | S | Med | B | 3 | Better replanning and rerouting from verbal diagnosis |
| 6 | Portfolio decomposition strategy | M | Med | B | 2 | Millisecond classical plans; LLM only when needed |
| 7 | Persist / Refine / Concede meta-reasoning | L | High | C | 1,3,4 | Cost-aware adaptation decisions; prevent wasteful LLM calls |
| 8 | Plan repair vs optimization separation | S | Med | D | 7 | Right algorithm for each adaptation type |
| 9 | Dynamic decomposition depth | M | High | C | 2,3 | Failed steps decompose finer instead of retrying |
| 10 | Learned action costs from CBR | M | High | C | 2 | Plans improve from experience; closed learning loop |
| 11 | Contingent planning branches | L | High | D | 2,9 | Pre-computed alternatives for predictable failures |

---

## 6. Embabel Comparison — Coverage Check

Embabel (Rod Johnson, Spring Boot/Kotlin, v1.5 GA) is the closest comparable GOAP-based agent framework. This section checks whether our epic misses anything Embabel does that we don't.

### What Embabel Has (verified against source at `/Users/mdproctor/claude/embabel-agent`)

**A* GOAP planner with dual optimization.** `AStarGoapPlanner` uses backward planning (removes actions not contributing to goal) and forward simulation (removes redundant actions). 10,000 iteration safety ceiling. Admissible heuristic: count of unsatisfied goal conditions. `OptimizingGoapPlanner.prune()` removes actions not used in any valid plan. Our `GoapPlanner` does forward-only A* search with soft preconditions — it lacks both optimizations.

This is a genuine gap. Backward pruning matters because case definitions can have 30+ bindings, many irrelevant to the current goal — pruning shrinks the search space before A* runs. Forward simulation matters because each redundant action in our system is a real worker execution consuming time, tokens, and agent capacity — not just a wasted animation frame as in game AI. Both optimizations produce *better* plans (shorter, no redundant steps) and scale better on larger action spaces.

**Recommendation:** Add backward pruning and forward simulation as enhancements to Issue #2 (GOAP as DecompositionStrategy). The planner we wire into the decomposition pipeline should include these optimizations from the start.

**Ternary world state.** `ConditionWorldState` uses TRUE/FALSE/UNKNOWN with lazy evaluation. When a condition is UNKNOWN, the `OptimizingGoapPlanner` generates all possible world states (condition true and false), plans for each, and only evaluates the condition at runtime if the two plans differ. This avoids unnecessary condition evaluation when the outcome doesn't affect the plan.

This is a genuine insight we lack. Our `GoapWorldState` is boolean (key present = true). Ternary logic with lazy evaluation would allow planning under partial observability without full contingent planning — a lighter-weight approach for cases where some context values are unknown at planning time.

**Recommendation:** Consider ternary world state as an enhancement to Issue #2 (GOAP as DecompositionStrategy). Not a separate issue — it's a refinement of the planner's world state model.

**Type-driven precondition/effect inference.** Method parameters → preconditions, return type → effects. Special types (`Ai`, `OperationContext`, `Blackboard`) filtered. This is Embabel's strongest ergonomic feature.

We already have this via the annotations module: `@Effect` for explicit effect keys, `GoapKeyConvention` for type → context key mapping, and parameter/return type inference in GOAP mode. No gap — our implementation is comparable.

**Dynamic cost computation.** `@Action(costMethod = "computeCost")` points to a method `(WorldState) → ZeroToOne` evaluated at planning time. Costs can depend on current blackboard state, other executed actions, and environment.

We have static `cost` on `GoapAction` and no equivalent of `@Cost` methods. Dynamic costs and CBR-learned costs are complementary, not substitutes:

- **Dynamic costs** see the *current* context — input data size, cache state, rate limit quotas, regulatory jurisdiction. CBR can't capture these because they vary per invocation, not per historical pattern.
- **CBR-learned costs** (Issue #10) see *historical performance* — average duration, success rate, reliability trends. Dynamic cost methods can't capture these because they require execution history.

The natural layering: dynamic costs as the planning-time base computation, CBR as a learned adjustment factor. When both are available: `effectiveCost = dynamicCost(context) × cbrReliabilityFactor`.

**Recommendation:** Add dynamic cost computation to Issue #2 (GOAP as DecompositionStrategy). In the annotations module: `@Cost`-annotated method taking context and returning a cost. In the YAML/builder path: JQ expression evaluated against the working layer at planning time. Issue #10 then layers learned costs on top — the planner's cost model becomes: static (declared) → dynamic (context-evaluated) → learned (CBR-adjusted), each layer enriching the previous.

**Tool replan decorators.** `replanAlways()`, `replanWhen(predicate)`, `replanAndAdd(blackboardObject)` wrap tools with explicit replan triggers. When an LLM tool call returns, the decorator can force replanning based on the result.

This is a novel pattern we don't have. Our replan triggers are event-based (`EveryStepTrigger`, `OnFailureTrigger`, proposed `ProgressGatedTrigger`). Embabel's approach lets individual actions declare "replanning is likely needed after I run" — a per-action hint rather than a global policy.

**Recommendation:** Consider per-binding replan hints as an enhancement to Issue #4 (Progress-Gated Trigger). A binding declaration like `replanAfter: true` would give the trigger a per-action signal. Not a separate issue — it enriches the trigger's input signals.

**Stuck handling.** `StuckHandler` SPI returns `REPLAN` or `NO_RESOLUTION`. When the planner cannot find a path, the handler can inject missing blackboard objects and request replanning. Failed actions are blacklisted from the next plan to prevent loops.

Our `OutcomePolicy` with REROUTE handles the "try a different agent" case. Our `GoalAbandonmentEvaluator` handles the "give up" case. The "inject missing state and retry" pattern is not explicit in our system — it's handled implicitly by context writes triggering re-evaluation. No gap in capability, but Embabel's explicit `StuckHandler` SPI is more intentional about the "fix the environment, then replan" recovery strategy. Issue #3 (Failure Taxonomy) covers this under `Knowledge` failure → replan.

**Blackboard spawn semantics.** Subagent processes get copy-on-write views of the parent blackboard. Changes in the child are isolated; the parent sees results only through explicit merging.

We have this via `SubCaseMapping` — sub-cases get projected input and write output back through configurable output mappings. The semantics are different (explicit projection vs. copy-on-write) but the isolation guarantee is equivalent.

### What Embabel Does NOT Have

| Capability | Status | Our epic coverage |
|-----------|--------|------------------|
| HTN decomposition | Absent — flat action space only | Existing capability |
| LLM-based decomposition | Absent — planning is deterministic only | Existing capability |
| Plan adaptation beyond replanning | Absent — replan is the sole mechanism | Issues #4, #7, #8 |
| Failure taxonomy | Absent — stuck/not-stuck binary only | Issue #3 |
| Progress-gated triggers | Absent | Issue #4 |
| Meta-reasoning (persist/refine/concede) | Absent | Issue #7 |
| CBR learning | Absent | Issue #10 |
| Multi-agent coordination | Subagent spawn only, no routing | Existing capability |
| Contingent planning | Absent | Issue #11 |
| Dynamic decomposition depth | Absent — no hierarchical refinement | Issue #9 |
| Plan repair vs optimization | Absent — replan is always full | Issue #8 |
| Reflexion-style critique | Absent | Issue #5 |

### Coverage Verdict

Our epic covers everything Embabel does and significantly more. Two minor enhancements worth folding into existing issues:

1. **Backward pruning and forward simulation** → enhance Issue #2 (GOAP Decomposition). Backward pruning removes actions that cannot contribute to the goal before A* search, shrinking the branching factor. Forward simulation strips redundant actions from the produced plan. Both matter more for case management (large action spaces, expensive actions) than for game AI. The GOAP decomposition strategy should include these from the start.

2. **Ternary world state with lazy evaluation** → refine Issue #2 (GOAP Decomposition). Allows planning under partial observability without the full weight of Issue #11 (Contingent Planning). Useful for cases where some context values are populated asynchronously.

3. **Per-binding replan hints** → refine Issue #4 (Progress-Gated Trigger). Let individual bindings declare `replanAfter: always | conditional | never` to give the trigger per-action signal strength alongside the global divergence measurement.

None require new issues. All strengthen existing issues — #1 is the most impactful.

### Broader Comparison

Embabel and casehub-engine operate at different layers. Embabel is a single-agent planning framework optimised for developer ergonomics — annotation-driven, type-inferred, Spring Boot integrated. CaseHub is a multi-tenant case orchestration platform where planning is one subsystem among many (routing, lifecycle, audit, governance, CBR learning).

Embabel's strengths are in the developer experience of declaring actions and goals. Its `@Action` → type inference → A* → execute → replan loop is clean and minimal. But it has no answer for: multi-agent coordination, failure-aware routing, experience-based learning, hierarchical decomposition, or enterprise governance of AI agent decisions.

The epic positions our planning subsystem to match Embabel's GOAP core (Issue #2), exceed it on adaptation intelligence (Issues #3-9), and add capabilities Embabel's architecture cannot support (Issues #10-11).
