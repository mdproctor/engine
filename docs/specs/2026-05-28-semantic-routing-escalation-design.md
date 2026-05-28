# Design Spec: Semantic Agent Routing + Borderline Escalation

**Issues:** engine#376, engine#377
**Branch:** `issue-376-377-semantic-routing-escalation`
**Date:** 2026-05-28
**Status:** Approved (rev 2 — post code review)

---

## Context

`TrustWeightedAgentStrategy` (engine#336) implements the four-phase trust maturity model
(protocol `trust-maturity-model.md`). Two capabilities are missing:

- **Phase 2 escalation (engine#377):** when all trust-eligible candidates are borderline
  (score within `borderlineMargin` of `threshold`), the strategy returns `noOp()` — a dead
  end. Phase 2 of the trust maturity model requires routing to human oversight, not silent
  failure.

- **Semantic routing (engine#376):** `AgentRoutingStrategy` has no access to the case
  context, so no strategy can match agent vocabulary against the situation being handled.
  Embedding-based selection was deliberately deferred from the original SPI design.

These two issues share the same architectural seam — `AgentAssignment`,
`AgentRoutingStrategy`, and the trust-scoring path — and must be designed together.

---

## Design

### 1. `AgentAssignment` — sealed type

The current `AgentAssignment` record collapses three semantically distinct routing
outcomes into one null discriminant. The engine's response to each is different; the
type must reflect that.

```java
// api/src/main/java/io/casehub/api/spi/routing/AgentAssignment.java
public sealed interface AgentAssignment
    permits AgentAssignment.Assigned,
            AgentAssignment.Unresolvable,
            AgentAssignment.EscalateToOversight {

  /** A specific worker was selected. */
  record Assigned(String workerId) implements AgentAssignment {}

  /**
   * No candidate passed trust filters. None were borderline — the pool lacks qualified
   * agents. Engine falls to tryProvision().
   */
  record Unresolvable() implements AgentAssignment {}

  /**
   * All trust-eligible candidates are borderline (score within borderlineMargin of
   * threshold). Engine must route to human oversight per trust-maturity-model.md Phase 2.
   */
  record EscalateToOversight(String capabilityName) implements AgentAssignment {}

  static AgentAssignment assign(String workerId)         { return new Assigned(workerId); }
  static AgentAssignment unresolvable()                   { return new Unresolvable(); }
  static AgentAssignment escalate(String capabilityName) { return new EscalateToOversight(capabilityName); }
}
```

`isNoOp()` is removed. All callers switch exhaustively on the sealed type.

**Breaking callers (all must be updated):**
- `AgentRoutingStrategyContractTest` — validate all three return variants
- `LeastLoadedAgentStrategy` — returns `Assigned` or `Unresolvable` (never escalates)
- `TrustWeightedAgentStrategy` — returns correct variant
- `CaseContextChangedEventHandler` — pattern-match switch
- `WorkOrchestrator` — see Section 8
- All `@QuarkusTest` mocks of `AgentRoutingStrategy`
- All test construction sites that call `AgentAssignment.noOp()` — replaced by
  `AgentAssignment.unresolvable()` or `AgentAssignment.escalate(...)` as appropriate

---

### 2. `AgentRoutingStrategy` — reactive SPI

The current `select()` is synchronous. `SemanticAgentRoutingStrategy` requires blocking
HTTP calls to an embedding service. Calling a blocking method on the Vert.x IO thread
(from a `@ConsumeEvent` handler returning `Uni<Void>`) is a runtime violation, not a
performance concern — Vert.x detects and logs IO-thread blocking under load.

The correct fix is a reactive SPI:

```java
// api/src/main/java/io/casehub/api/spi/routing/AgentRoutingStrategy.java
public interface AgentRoutingStrategy {   // @FunctionalInterface removed

  /**
   * Select a worker from the pre-filtered candidate list.
   *
   * Implementations that do only in-memory work return Uni.createFrom().item(result).
   * Implementations that call blocking services (e.g. embedding providers) return a
   * reactive chain that executes on an appropriate executor — never blocking the IO
   * thread.
   */
  Uni<AgentAssignment> select(AgentRoutingContext context, List<AgentCandidate> candidates);
}
```

All callers chain `agentRoutingStrategy.select(ctx, candidates)` into the existing
`Uni<Void>` pipeline.

**`LeastLoadedAgentStrategy`:**
```java
public Uni<AgentAssignment> select(...) {
    return Uni.createFrom().item(() -> /* existing logic */);
}
```

**`TrustWeightedAgentStrategy`:**
```java
public Uni<AgentAssignment> select(...) {
    return Uni.createFrom().item(() -> /* existing logic */);
}
```

**`SemanticAgentRoutingStrategy`:**
```java
public Uni<AgentAssignment> select(...) {
    // classify synchronously (in-memory trust cache)
    List<ClassifiedCandidate> classified = classifier.classify(...);
    // collect QUALIFIED candidates needing embedding
    // embed in blocking context, then decide
    return Uni.createFrom().voidItem()
        .emitOn(Infrastructure.getDefaultWorkerPool())  // move off IO thread
        .map(ignored -> { /* blocking embed + decide */ });
}
```

**`WorkOrchestrator`:** currently uses `CompletableFuture`. Convert the call site to
`.subscribe().asCompletionStage()`, or wrap in a `Uni.createFrom().item()` call on the
caller's thread. See Section 8 for full `WorkOrchestrator` treatment.

**`@FunctionalInterface` removed:** lambdas still work as implementations since
`Uni<AgentAssignment>` lambdas are valid for the SAM shape — but the annotation no
longer applies because lambdas returning `Uni` may not be concretely inferable without
explicit typing in all cases. Implementations are concrete classes.

---

### 3. `TrustRoutingPolicy` — phase predicate methods

Per GE-20260511-2b3d3e: trust-phase logic belongs on the value object, not the router.

```java
// ledger/src/main/java/io/casehub/ledger/routing/TrustRoutingPolicy.java
public record TrustRoutingPolicy(...) {

  /** True when an agent lacks sufficient decision history for trust-based routing. */
  public boolean isBootstrap(int decisionCount) {
    return decisionCount < minimumObservations;
  }

  /**
   * True when the trust score is close to the threshold in either direction.
   * Note: a borderline candidate is NOT qualified for assignment — it is excluded.
   * Borderline is a distinct Phase 2a state, not a passing check.
   */
  public boolean isBorderline(double score) {
    return Math.abs(score - threshold) <= borderlineMargin;
  }

  /**
   * True when the score exceeds the threshold and is not borderline.
   * This is a Phase 2 first-pass check only — Phase 3 quality floors may still
   * exclude a candidate that passes this check. Do not interpret as "ready to assign".
   */
  public boolean passesThresholdCheck(double score) {
    return score >= threshold && !isBorderline(score);
  }
}
```

`isBorderline` and `passesThresholdCheck` are named to reflect their meaning precisely
rather than implying a final verdict. Both strategies call these instead of duplicating
the arithmetic.

---

### 4. `TrustCandidateClassifier` — `@ApplicationScoped` CDI bean

Both `TrustWeightedAgentStrategy` and `SemanticAgentRoutingStrategy` share the same
4-phase classification loop and the same outcome decision. This utility is extracted as
a CDI bean so it is discoverable via the standard Quarkus CDI path (no Maven visibility
hacks required — both consumers depend on `casehub-engine-ledger`).

`classify()` and `decide()` accept all data as parameters. There is no mutable state;
singleton (`@ApplicationScoped`) is safe and correct.

```java
// ledger/src/main/java/io/casehub/ledger/routing/TrustCandidateClassifier.java
@ApplicationScoped
public class TrustCandidateClassifier {

  /**
   * Phase classification for a single candidate.
   *
   * BOOTSTRAP        — Phase 0/1: insufficient history; workload routing applies.
   * QUALIFIED        — Phase 2/3 passed: trust + workload blend applies.
   * BORDERLINE       — Phase 2a: score within margin of threshold; excluded.
   *                    Tracked separately from EXCLUDED for escalation decisions.
   * EXCLUDED_PHASE2B — Phase 2b: score below threshold; excluded.
   * EXCLUDED_PHASE3  — Phase 3: passed threshold but failed a quality floor; excluded.
   *                    Distinct from EXCLUDED_PHASE2B for future diagnostic use.
   */
  public enum Phase {
    BOOTSTRAP, QUALIFIED, BORDERLINE, EXCLUDED_PHASE2B, EXCLUDED_PHASE3
  }

  public record ClassifiedCandidate(
      AgentCandidate candidate,
      Phase phase,
      OptionalDouble trustScore,   // empty for BOOTSTRAP (no signal); present otherwise
      double workloadScore         // 1/(1+runningJobs) — always computed
  ) {
    /** Convenience: true when this candidate is any form of excluded. */
    public boolean isExcluded() {
      return phase == Phase.BORDERLINE
          || phase == Phase.EXCLUDED_PHASE2B
          || phase == Phase.EXCLUDED_PHASE3;
    }
  }

  public List<ClassifiedCandidate> classify(
      List<AgentCandidate> candidates,
      String capabilityName,
      TrustRoutingPolicy policy,
      TrustScoreCache cache
  ) { /* full 4-phase classification */ }

  /**
   * Given classified candidates and their final scores (after strategy-specific
   * scoring), determine the routing outcome.
   *
   * Rule: if any candidate scored > 0.0 → Assigned(highest).
   *       if all scored 0.0 and any was BORDERLINE → EscalateToOversight.
   *       if all scored 0.0 and none was BORDERLINE → Unresolvable.
   */
  public AgentAssignment decide(
      List<ClassifiedCandidate> classified,
      List<ScoredCandidate> scored,
      String capabilityName
  ) { /* outcome decision */ }

  /** Internal record — candidate + its final routing score. */
  public record ScoredCandidate(ClassifiedCandidate classified, double finalScore) {}
}
```

`OptionalDouble trustScore` enforces at the type level that BOOTSTRAP candidates carry
no trust signal. Any code that reads `trustScore` must handle the empty case; it cannot
accidentally receive NaN.

`EXCLUDED_PHASE2B` vs `EXCLUDED_PHASE3` preserves the diagnostic information about why
a candidate was excluded. The `decide()` outcome logic is unchanged — both are non-
borderline exclusions leading to `Unresolvable` when all candidates are in this state.

---

### 5. `TrustWeightedAgentStrategy` — refactored

Uses `TrustCandidateClassifier` (injected) and the `TrustRoutingPolicy` predicate methods:

```
classify(candidates) → List<ClassifiedCandidate>
for each classified:
    BOOTSTRAP         → workloadScore  (unchanged Gastown parity)
    QUALIFIED         → trust × blendFactor + workload × (1-blendFactor)
    BORDERLINE        → 0.0
    EXCLUDED_PHASE2B  → 0.0
    EXCLUDED_PHASE3   → 0.0
decide(classified, scored, capabilityName) → AgentAssignment
return Uni.createFrom().item(result)
```

Scoring algorithm unchanged. Mixed-pool policy preserved: BOOTSTRAP always produces a
positive workload score, so bootstrap candidates are selected before the all-zero case
triggers escalation.

---

### 6. `AgentRoutingContext` — add `caseContext`

```java
// api/src/main/java/io/casehub/api/spi/routing/AgentRoutingContext.java
public record AgentRoutingContext(UUID caseId, String capabilityName, JsonNode caseContext) {}
```

**Construction sites (all must pass caseContext):**
- `CaseContextChangedEventHandler` — `objectMapper.valueToTree(caseInstance.getContext())`
- `WorkOrchestrator` — same pattern; `objectMapper` is already in scope
- All test construction sites of `AgentRoutingContext` — two-arg form no longer compiles;
  every test file must be updated. Use `NullNode.instance` or a test fixture for tests
  that don't exercise semantic routing.

`LeastLoadedAgentStrategy` and `TrustWeightedAgentStrategy` ignore `caseContext`.

---

### 7. `AgentCandidate` — add `agentDescriptor`

```java
// api/src/main/java/io/casehub/api/spi/routing/AgentCandidate.java
public record AgentCandidate(
    String workerId,
    Set<String> capabilities,
    int runningJobs,
    AgentHealth health,
    AgentDescriptor agentDescriptor   // nullable; null if no descriptor registered
) {}
```

`AgentDescriptor` from `casehub-eidos-api` is already a transitive dependency of
`casehub-engine-api` (used in `Worker.java`). No new module dependency.

When `agentDescriptor` is null, `SemanticAgentRoutingStrategy` treats the candidate as
BOOTSTRAP (availability routing; no semantic signal available). This preserves the
"never block on missing data" invariant.

---

### 8. `AgentCandidateFactory` — shared construction utility

Both `CaseContextChangedEventHandler` and `WorkOrchestrator` independently implement
`buildCandidates()`. Both now need the same update (add `agentDescriptor`). Rather than
update both independently, extract to a shared static utility:

```java
// runtime/src/main/java/io/casehub/engine/internal/routing/AgentCandidateFactory.java
public final class AgentCandidateFactory {

  private AgentCandidateFactory() {}

  public static List<AgentCandidate> buildCandidates(
      CaseInstance caseInstance,
      List<Worker> workers,
      Capability capability,
      WorkerExecutionManager executionManager,
      CapabilityHealth capabilityHealth
  ) { /* shared implementation — moved from both handlers */ }
}
```

Both handlers call `AgentCandidateFactory.buildCandidates(...)`. The existing logic
(health probing, filtering unavailable workers, computing `runningJobs`) is unchanged.
`worker.agentDescriptor()` is passed through; null if the worker has no registered
descriptor.

---

### 9. `CaseChannel` — `oversightChannelName` convenience method

The escalation handler needs to find the oversight channel by name. Spelling out a string
literal at each call site is fragile. Add a static convenience consistent with the
existing `channelName(UUID, String)` pattern:

```java
// casehub-engine-common/.../CaseChannel.java
public static String oversightChannelName(UUID caseId) {
    return channelName(caseId, "oversight");
}
```

The escalation handler calls `CaseChannel.oversightChannelName(caseId)` and passes the
result to `CaseChannelProvider.listChannels(caseId).stream().filter(c ->
c.name().equals(oversightName)).findFirst()`.

---

### 10. Engine handler — pattern-match on `AgentAssignment`

`CaseContextChangedEventHandler.publishWorkerSchedule()`:

```java
agentRoutingStrategy.select(ctx, candidates)
    .chain(assignment -> switch (assignment) {
        case AgentAssignment.Assigned a ->
            scheduleWorker(caseInstance, workers, a.workerId(), binding, capability);
        case AgentAssignment.Unresolvable() ->
            tryProvision(caseInstance, capability);
        case AgentAssignment.EscalateToOversight e ->
            handleEscalation(caseInstance, e, binding);
    });
```

**`handleEscalation`:** publishes `AgentRoutingEscalationEvent` to Vert.x event bus at
`EventBusAddresses.AGENT_ROUTING_ESCALATION`. PlanItem stays PENDING.

```java
record AgentRoutingEscalationEvent(
    UUID caseId,
    String capabilityName,
    String bindingName
) {}
```

---

### 11. `AgentRoutingEscalationHandler`

```java
@ApplicationScoped
public class AgentRoutingEscalationHandler {

  @ConsumeEvent(value = EventBusAddresses.AGENT_ROUTING_ESCALATION, blocking = true)
  public void handle(AgentRoutingEscalationEvent event) {
    String oversightName = CaseChannel.oversightChannelName(event.caseId());
    channelProvider.listChannels(event.caseId()).stream()
        .filter(c -> c.name().equals(oversightName))
        .findFirst()
        .ifPresentOrElse(
            channel -> postQuery(channel, event),
            () -> LOG.warnf(
                "[METRIC:escalation.no-oversight-channel] caseId=%s capability=%s — " +
                "escalation swallowed; no oversight channel open. " +
                "In production deployments this PlanItem will remain PENDING indefinitely. " +
                "engine#379 tracks PlanItem state handling during escalation.",
                event.caseId(), event.capabilityName())
        );
  }
}
```

The "no oversight channel" case is an operational gap, not just a log line. The warning
message is prefixed `[METRIC:escalation.no-oversight-channel]` so it can be wired to an
alert in log-based monitoring. In dev/test with `NoOpCaseChannelProvider`
(`listChannels()` returns `List.of()`), escalation is silently absorbed — this is
expected test behavior and is documented in the handler.

---

### 12. `WorkOrchestrator` — three required updates

1. **`AgentRoutingContext` construction:** add `caseContext` argument.
   `objectMapper.valueToTree(instance.getCaseContext())`.

2. **`buildCandidates()` → `AgentCandidateFactory.buildCandidates(...)`:** same pattern
   as `CaseContextChangedEventHandler`.

3. **`select()` return type and `EscalateToOversight` handling:**
   `doSubmit()` calls `agentRoutingStrategy.select(ctx, candidates)` synchronously
   (inside a CompletableFuture chain on a non-IO thread). Wrap in `.subscribe().asCompletionStage()`.
   When the result is `EscalateToOversight`: publish the escalation event to the Vert.x
   event bus (same path as `CaseContextChangedEventHandler.handleEscalation`). The
   CompletableFuture completes with an empty result (no worker scheduled). Completing
   exceptionally would signal failure; the escalation is not a failure — it is a pending
   governance decision.

---

### 13. New module: `casehub-engine-ai`

An optional module. When NOT on the classpath: `TrustWeightedAgentStrategy` is the
highest-priority routing strategy. When on the classpath:
`SemanticAgentRoutingStrategy` (@Priority(2)) activates.

**Maven module setup:**
- Add `<module>casehub-engine-ai</module>` to root `pom.xml`
- Set `<maven.deploy.skip>true</maven.deploy.skip>` in the module's `pom.xml` — this is
  optional infrastructure without a stable embedding provider; do not publish to GitHub
  Packages until engine#381 ships
- No separate version property needed — follows the engine parent POM version

**`casehub-engine-ai` dependencies:**
- `casehub-engine-api` (AgentRoutingStrategy SPI, AgentCandidate, AgentRoutingContext)
- `casehub-engine-ledger` (TrustCandidateClassifier, TrustScoreCache, TrustRoutingPolicyProvider)
- `casehub-engine-common` (JQEvaluator)
- `casehub-eidos-api` (AgentDescriptor, AgentCapability)

**`AgentEmbeddingProvider` SPI:**

```java
// casehub-engine-ai/src/main/java/io/casehub/engine/ai/spi/AgentEmbeddingProvider.java
public interface AgentEmbeddingProvider {

  /**
   * Embed text to a float vector. May block (network IO to embedding service).
   * Callers must invoke from a worker thread, never from the Vert.x IO thread.
   *
   * <p>Implementations MUST be thread-safe — this method may be called concurrently
   * from multiple routing decisions.
   */
  float[] embed(String text);

  /** Cosine similarity. Returns 0.0 for zero vectors (no NaN). */
  static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0, magA = 0, magB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i]; magA += a[i] * a[i]; magB += b[i] * b[i];
    }
    return (magA == 0 || magB == 0) ? 0.0 : dot / (Math.sqrt(magA) * Math.sqrt(magB));
  }
}
```

No `@DefaultBean`. Including `casehub-engine-ai` without a provider causes an
unsatisfied dependency error at CDI startup. This is intentional: semantic routing
without an embedding model is a misconfiguration.

A LangChain4j-backed implementation is tracked in **engine#381**.

**Config:**
```properties
# Weight of semantic similarity in the final score
# (0.0 = pure trust+workload, 1.0 = pure semantic)
casehub.engine.ai.semantic-weight=0.4

# JQ expression applied to caseContext before embedding; identity by default
casehub.engine.ai.context-summary-jq=.
```

**`SemanticAgentRoutingStrategy`** (`@Alternative @Priority(2) @ApplicationScoped`):

```
classify(candidates, capabilityName, policy, cache) → List<ClassifiedCandidate>

// Executed on worker thread (emitOn):
queryText  = extractQueryText(context.caseContext(), context.capabilityName())
queryVector = embeddingProvider.embed(queryText)

for each classified candidate:
    BOOTSTRAP            → workloadScore (Gastown parity; no semantic signal)
    BORDERLINE           → 0.0 (tracked; escalation if all excluded)
    EXCLUDED_PHASE2B/3   → 0.0
    QUALIFIED (descriptor non-null) →
        docText   = buildVocabularyText(candidate.agentDescriptor())
        docVector = embeddingProvider.embed(docText)
        semantic  = cosineSimilarity(queryVector, docVector)
        trustBlend = trustScore × blendFactor + workload × (1-blendFactor)
        finalScore = semantic × semanticWeight + trustBlend × (1-semanticWeight)
        // effective weights at defaults (semanticWeight=0.4, blendFactor=0.6):
        //   semantic=0.40, trust=0.36, workload=0.24 — sums to 1.0
    QUALIFIED (descriptor null) →
        // no descriptor → treat as BOOTSTRAP (availability routing only)

decide(classified, scored, capabilityName) → AgentAssignment
return Uni via Infrastructure.getDefaultWorkerPool()
```

**`extractQueryText` method:**

```java
private String extractQueryText(JsonNode caseContext, String capabilityName) {
    if (caseContext == null || caseContext.isNull() || caseContext.isMissingNode()) {
        return capabilityName;   // capability name has semantic content; "null" does not
    }
    ValidationResult result = jqEvaluator.eval(contextSummaryJq, caseContext);
    if (result.hasErrors() || result.results().isEmpty()) {
        LOG.warnf("caseContext JQ extraction failed for jq='%s'; falling back to capability name '%s'",
            contextSummaryJq, capabilityName);
        return capabilityName;
    }
    String extracted = result.results().stream()
        .map(JsonNode::asText)
        .filter(s -> !s.isBlank())
        .collect(Collectors.joining(" "));
    return extracted.isBlank() ? capabilityName : extracted;
}
```

`JQEvaluator.eval(String expression, JsonNode input)` is the existing API — no new
methods added to `JQEvaluator`. The fallback to `capabilityName` ensures the embedding
call always receives meaningful text.

**Vocabulary text construction** from `AgentDescriptor`:

```
{domainVocabulary} {slotVocabulary} {dispositionVocabulary}
capability:{name} tags:{tags.join(" ")} domains:{epistemicDomains.keySet().join(" ")}
```

One text string per candidate, embedded fresh at each routing call. Vector caching is
deferred to **engine#380**.

---

### 14. Testing

| Test class | Module | Covers |
|---|---|---|
| `AgentAssignmentTest` | `api` | Sealed variants, factory methods, compile-time exhaustiveness |
| `AgentRoutingStrategyContractTest` | `api` | Uni<AgentAssignment> return type; three variants; no @FunctionalInterface |
| `TrustRoutingPolicyTest` | `ledger` | `isBootstrap`, `isBorderline`, `passesThresholdCheck` — edge cases on boundary |
| `TrustCandidateClassifierTest` | `ledger` | All Phase enum values; quality floor → EXCLUDED_PHASE3; BOOTSTRAP OptionalDouble empty; decide() Escalate vs Unresolvable |
| `TrustWeightedAgentStrategyTest` | `ledger` | New: all-borderline → EscalateToOversight; mix borderline+excluded_p2b → EscalateToOversight; all excluded no borderline → Unresolvable; BOOTSTRAP+borderline → Assigned; Uni return |
| `SemanticAgentRoutingStrategyTest` | `casehub-engine-ai` | Mock embedding provider; semantic re-ranking; escalation path; null descriptor → BOOTSTRAP; null caseContext → capability name fallback; empty JQ result → capability name fallback |
| `CaseContextChangedEventHandlerTest` | `engine` | Pattern-match: each sealed variant triggers correct downstream; Uni chain correctness |
| `AgentRoutingEscalationHandlerTest` | `engine` | Oversight channel found → postToChannel called; channel not found → warning log with METRIC prefix |
| `WorkOrchestratorTest` | `engine` | EscalateToOversight → escalation event published; Unresolvable → existing path; Assigned → schedule |
| `AgentCandidateFactoryTest` | `engine` | Shared construction, null descriptor passthrough |

---

## Protocol coherence review

| Protocol | Status |
|---|---|
| `trust-maturity-model.md` — Phase 2 → HumanOversight | ✅ `EscalateToOversight` maps to Phase 2; posts QUERY to oversight channel |
| `no-workarounds-fix-the-design.md` — break callers | ✅ Sealed type, reactive SPI, record extensions all break callers correctly |
| `engine-spi-noops-defaultbean.md` — no-op defaults for SPI | `AgentEmbeddingProvider` has NO default (intentional startup-fail on misconfiguration) |
| `spi-case-id-parameter.md` — caseId through all SPI methods | ✅ `AgentRoutingContext` carries `caseId`; `AgentEmbeddingProvider` is stateless |
| `qhorus-human-governance-channel-types.md` — oversight QUERY,COMMAND only | ✅ escalation handler posts `MessageType.QUERY` |
| `qhorus-per-entity-governance-channels.md` — name by entity not actor | ✅ `oversightChannelName(caseId)` = `case-{caseId}/oversight` (entity-scoped) |
| GE-20260511-2b3d3e — phase logic on policy value object | ✅ `TrustRoutingPolicy.isBootstrap/isBorderline/passesThresholdCheck` |

---

## Issues to file before implementation

| Issue | Title | Reason |
|---|---|---|
| engine#378 | Oversight response loop: COMMAND from human → re-trigger routing | Requires listening on oversight channels — separate handler lifecycle |
| engine#379 | PlanItem state during escalation (ESCALATING state, blackboard autocomplete semantics) | Must not be decided ad hoc at implementation time |
| engine#380 | Embedding vector cache: per-(workerId, capabilityName) to amortise embed() cost | Scope; not needed for correctness but will be needed for production load |
| engine#381 | LangChain4j `AgentEmbeddingProvider` implementation | Consumer-scope; lives in downstream module |
| parent#80 | PLATFORM.md "Worker routing/selection" stale (still references casehub-work WorkBroker) | Already filed; verify open |

---

## Out of scope

- Response handling when human responds to escalation QUERY (engine#378)
- PlanItem ESCALATING state (engine#379)
- Embedding vector caching (engine#380)
- LangChain4j `AgentEmbeddingProvider` implementation (engine#381)
- Per-case dynamic strategy dispatch — `spi-case-id-parameter.md` ensures the door
  remains open; no implementation required
