# PlanAdapter CBR Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #738 — wire PlanAdapter into CbrRetrievalService pipeline
**Issue group:** #738

**Goal:** Wire the neocortex `PlanAdapter` SPI into the engine's `CbrRetrievalService`
so plan adaptation happens automatically for all apps during CBR retrieval.

**Architecture:** `CbrRetrievalService` injects `PlanAdapter` (blocking SPI, same pattern
as `CbrCaseMemoryStore`). For `PlanCbrCase` results, adaptation runs inside `mapScoredCase()`
— no separate pipeline stage. `ExperiencePlanStep` carries adaptation signal as nullable
String fields. `ExperienceAnalyser` filters ADDED steps from statistical computation.

**Tech Stack:** Java 21, Quarkus 3.32, Mutiny, jackson-jq

## Global Constraints

- neocortex `0.2-SNAPSHOT` with neocortex#161 (caseType on PlanAdapter) must be installed
  in local maven before building
- `ExperiencePlanStep` is in `engine-api` — no neocortex type imports allowed
- `CbrRetrievalService` injects blocking SPIs directly per GE-20260706-abaddc
- Adaptation failure must never block case progression
- All existing tests must continue to pass unchanged (convenience constructor preserves
  the 6-arg call sites)

---

### Task 1: ExperiencePlanStep Enrichment + ExperienceAnalyser ADDED Filtering

**Files:**
- Modify: `api/src/main/java/io/casehub/api/spi/routing/ExperiencePlanStep.java`
- Modify: `api/src/main/java/io/casehub/api/spi/routing/ExperienceAnalyser.java`
- Modify: `api/src/test/java/io/casehub/api/spi/routing/ExperiencePlanStepTest.java`
- Modify: `api/src/test/java/io/casehub/api/spi/routing/ExperienceAnalyserTest.java`

**Interfaces:**
- Produces: `ExperiencePlanStep(String, String, String, String, int, Map, String, String)` — 8-arg canonical constructor
- Produces: `ExperiencePlanStep(String, String, String, String, int, Map)` — 6-arg convenience constructor (null adaptation fields)
- Produces: `ExperiencePlanStep.adaptationAction()` — nullable String accessor
- Produces: `ExperiencePlanStep.adaptationReason()` — nullable String accessor
- Produces: `ExperienceAnalyser.workerSuccessRates()` now skips steps where `adaptationAction` equals `"ADDED"`

- [ ] **Step 1: Write failing test — 8-arg constructor with adaptation fields**

Add to `ExperiencePlanStepTest.java`:

```java
@Test
void adaptation_fields_populated() {
    var step = new ExperiencePlanStep(
        "bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(),
        "BOOSTED", "high relevance to current case");
    assertEquals("BOOSTED", step.adaptationAction());
    assertEquals("high relevance to current case", step.adaptationReason());
}

@Test
void adaptation_fields_nullable() {
    var step = new ExperiencePlanStep(
        "bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(),
        null, null);
    assertNull(step.adaptationAction());
    assertNull(step.adaptationReason());
}

@Test
void convenience_constructor_nulls_adaptation_fields() {
    var step = new ExperiencePlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    assertNull(step.adaptationAction());
    assertNull(step.adaptationReason());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn test -pl api -Dtest="ExperiencePlanStepTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: compilation failure — 8-arg constructor does not exist.

- [ ] **Step 3: Implement ExperiencePlanStep changes**

Replace the `ExperiencePlanStep` record declaration with:

```java
public record ExperiencePlanStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters,
    String adaptationAction,
    String adaptationReason) {

  public ExperiencePlanStep {
    Objects.requireNonNull(bindingName, "bindingName must not be null");
    Objects.requireNonNull(capabilityName, "capabilityName must not be null");
    if (priority < 0) {
      throw new IllegalArgumentException("priority must be non-negative, got: " + priority);
    }
    parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
  }

  public ExperiencePlanStep(
      String bindingName,
      String capabilityName,
      String workerName,
      String stepOutcome,
      int priority,
      Map<String, Object> parameters) {
    this(bindingName, capabilityName, workerName, stepOutcome, priority, parameters, null, null);
  }
}
```

- [ ] **Step 4: Run ExperiencePlanStep tests to verify they pass**

```bash
/opt/homebrew/bin/mvn test -pl api -Dtest="ExperiencePlanStepTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: all tests PASS (existing 6-arg tests use the convenience constructor unchanged).

- [ ] **Step 5: Write failing test — ExperienceAnalyser skips ADDED steps**

Add to `ExperienceAnalyserTest.java`:

```java
@Test
void addedSteps_excludedFromStatistics() {
    var addedStep = new ExperiencePlanStep(
        "binding-agent-a", "security-review", "agent-a", "SUCCESS", 0, Map.of(),
        "ADDED", "adapter recommendation");
    var retainedStep = new ExperiencePlanStep(
        "binding-agent-b", "security-review", "agent-b", "SUCCESS", 0, Map.of(),
        "RETAINED", null);
    var exp = new RetrievedExperience(
        "problem", "solution", "COMPLETED", 1.0, 0.8, Map.of(),
        List.of(addedStep, retainedStep), Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a", "agent-b"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).doesNotContainKey("agent-a");
    assertThat(result).containsEntry("agent-b", 1.0);
}

@Test
void nonAddedAdaptationSteps_includedInStatistics() {
    var boostedStep = new ExperiencePlanStep(
        "binding-agent-a", "security-review", "agent-a", "SUCCESS", 0, Map.of(),
        "BOOSTED", "high relevance");
    var exp = new RetrievedExperience(
        "problem", "solution", "COMPLETED", 1.0, 0.8, Map.of(),
        List.of(boostedStep), Map.of());
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
}

@Test
void nullAdaptationAction_includedInStatistics() {
    var unadaptedStep = step("agent-a", "security-review", "SUCCESS");
    var exp = experience(0.8, unadaptedStep);
    Map<String, Double> result =
        ExperienceAnalyser.workerSuccessRates(
            List.of(exp),
            Set.of("agent-a"),
            "security-review",
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    assertThat(result).containsEntry("agent-a", 1.0);
}
```

- [ ] **Step 6: Run ExperienceAnalyser tests to verify new tests fail**

```bash
/opt/homebrew/bin/mvn test -pl api -Dtest="ExperienceAnalyserTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: `addedSteps_excludedFromStatistics` FAILS (ADDED step is included, agent-a appears in results).

- [ ] **Step 7: Implement ExperienceAnalyser ADDED filtering**

In `ExperienceAnalyser.workerSuccessRates()`, add a filter at line 74 inside the plan trace loop, after the existing capability/worker/null checks:

```java
for (final ExperiencePlanStep step : exp.planTrace()) {
    if (!capabilityName.equals(step.capabilityName())
        || step.workerName() == null
        || !eligibleWorkerIds.contains(step.workerName())) {
      continue;
    }

    if ("ADDED".equals(step.adaptationAction())) {
      continue;
    }

    // ... rest of method unchanged
```

- [ ] **Step 8: Run all api tests to verify everything passes**

```bash
/opt/homebrew/bin/mvn test -pl api -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: all tests PASS including existing `RetrievedExperienceTest` and `TrustWeightedAgentStrategyTest` (via convenience constructor).

- [ ] **Step 9: Verify no compilation issues across the project**

```bash
/opt/homebrew/bin/mvn compile -DskipTests -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Also run `ide_diagnostics` on both changed files to catch anything the compiler misses.

- [ ] **Step 10: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/engine add api/src/main/java/io/casehub/api/spi/routing/ExperiencePlanStep.java api/src/main/java/io/casehub/api/spi/routing/ExperienceAnalyser.java api/src/test/java/io/casehub/api/spi/routing/ExperiencePlanStepTest.java api/src/test/java/io/casehub/api/spi/routing/ExperienceAnalyserTest.java
git -C /Users/mdproctor/claude/casehub/engine commit -m "feat(#738): enrich ExperiencePlanStep with adaptation fields, filter ADDED in analyser

Add nullable adaptationAction and adaptationReason to ExperiencePlanStep.
Convenience 6-arg constructor preserves all existing call sites.
ExperienceAnalyser.workerSuccessRates() skips ADDED steps — adapter
recommendations must not create phantom historical evidence.

Refs #738"
```

---

### Task 2: Wire PlanAdapter into CbrRetrievalService

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/routing/CbrRetrievalService.java`
- Modify: `runtime/src/test/java/io/casehub/engine/internal/routing/CbrRetrievalServiceTest.java`

**Interfaces:**
- Consumes: `ExperiencePlanStep(String, String, String, String, int, Map, String, String)` — 8-arg from Task 1
- Consumes: `ExperiencePlanStep(String, String, String, String, int, Map)` — 6-arg convenience from Task 1
- Consumes: `PlanAdapter.adapt(String caseType, ScoredCbrCase<PlanCbrCase>, Map<String, FeatureValue>)` — from neocortex#161
- Consumes: `AdaptedPlan.steps()` — `List<AdaptedStep>`
- Consumes: `AdaptedStep.action()` — `AdaptationAction` enum
- Consumes: `AdaptedStep.reason()` — nullable String
- Consumes: `AdaptationAction.REMOVED` — filter constant
- Produces: `CbrRetrievalService(JQEvaluator, CbrCaseMemoryStore, PlanAdapter)` — 3-arg test constructor
- Produces: Adapted `ExperiencePlanStep` entries in `RetrievedExperience.planTrace()` for PlanCbrCase results

- [ ] **Step 1: Write failing test — adaptation wired for PlanCbrCase**

Add a recording `PlanAdapter` stub and a test to `CbrRetrievalServiceTest.java`:

```java
static class RecordingPlanAdapter implements PlanAdapter {
    private boolean called;
    private String lastCaseType;
    private AdaptedPlan result;

    void setResult(AdaptedPlan result) {
        this.result = result;
    }

    boolean wasCalled() {
        return called;
    }

    String lastCaseType() {
        return lastCaseType;
    }

    @Override
    public AdaptedPlan adapt(String caseType, ScoredCbrCase<PlanCbrCase> retrieved,
                             Map<String, FeatureValue> currentFeatures) {
        called = true;
        lastCaseType = caseType;
        if (result != null) {
            return result;
        }
        return new AdaptedPlan(
            retrieved.cbrCase().planTrace().stream()
                .map(t -> new AdaptedStep(
                    t.bindingName(), t.capabilityName(), t.workerName(),
                    t.stepOutcome(), t.priority(), t.parameters(),
                    AdaptationAction.RETAINED, null))
                .toList()
        );
    }
}
```

Update `setUp()` to create service with the recording adapter:

```java
private RecordingPlanAdapter planAdapter;

@BeforeEach
void setUp() throws Exception {
    jqEvaluator = new JQEvaluator();
    Method init = JQEvaluator.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(jqEvaluator);
    cbrStore = new RecordingCbrStore();
    planAdapter = new RecordingPlanAdapter();
    service = new CbrRetrievalService(jqEvaluator, cbrStore, planAdapter);
}
```

Add tests:

```java
@Test
void planCbrCase_adaptationInvoked() {
    CbrConfig config = CbrConfig.builder()
        .featureExtractor(ctx -> Map.of("f1", "v1"))
        .domain("test").build();
    CaseDefinition def = buildDefinition(config);
    PlanTrace pt = new PlanTrace("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    PlanCbrCase planCase = new PlanCbrCase(
        "problem1", "solution1", "COMPLETED", 0.95,
        Map.of("f1", FeatureValue.string("v1")), List.of(pt));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(planCase, 0.87)));

    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();

    assertTrue(planAdapter.wasCalled());
    assertEquals("test-case", planAdapter.lastCaseType());
    assertEquals(1, result.size());
    assertEquals("RETAINED", result.get(0).planTrace().get(0).adaptationAction());
}

@Test
void planCbrCase_caseType_threaded_from_config() {
    CbrConfig config = CbrConfig.builder()
        .featureExtractor(ctx -> Map.of("f1", "v1"))
        .domain("test").caseType("custom-type").build();
    CaseDefinition def = buildDefinition(config);
    PlanCbrCase planCase = new PlanCbrCase(
        "problem1", "solution1", "COMPLETED", 0.9,
        Map.of("f1", FeatureValue.string("v1")),
        List.of(new PlanTrace("b1", "c1", "w1", "SUCCESS", 0, Map.of())));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(planCase, 0.8)));

    service.retrieve(def, buildInstance()).await().indefinitely();

    assertEquals("custom-type", planAdapter.lastCaseType());
}

@Test
void planCbrCase_removedSteps_filtered() {
    CbrConfig config = CbrConfig.builder()
        .featureExtractor(ctx -> Map.of("f1", "v1"))
        .domain("test").build();
    CaseDefinition def = buildDefinition(config);
    PlanCbrCase planCase = new PlanCbrCase(
        "problem1", "solution1", "COMPLETED", 0.9,
        Map.of("f1", FeatureValue.string("v1")),
        List.of(new PlanTrace("b1", "c1", "w1", "SUCCESS", 0, Map.of()),
                new PlanTrace("b2", "c2", "w2", "FAILURE", 0, Map.of())));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(planCase, 0.8)));

    planAdapter.setResult(new AdaptedPlan(List.of(
        new AdaptedStep("b1", "c1", "w1", "SUCCESS", 0, Map.of(),
            AdaptationAction.RETAINED, null),
        new AdaptedStep("b2", "c2", "w2", "FAILURE", 0, Map.of(),
            AdaptationAction.REMOVED, "irrelevant to current case")
    )));

    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();

    assertEquals(1, result.get(0).planTrace().size());
    assertEquals("b1", result.get(0).planTrace().get(0).bindingName());
}

@Test
void featureVectorCase_adapterNotCalled() {
    CbrConfig config = CbrConfig.builder()
        .featureExtractor(ctx -> Map.of("f1", "v1"))
        .domain("test").cbrType("feature-vector").build();
    CaseDefinition def = buildDefinition(config);
    FeatureVectorCbrCase fvCase = new FeatureVectorCbrCase(
        "problem1", "solution1", "COMPLETED", 0.9,
        Map.of("f1", FeatureValue.string("v1")));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(fvCase, 0.85)));

    service.retrieve(def, buildInstance()).await().indefinitely();

    assertFalse(planAdapter.wasCalled());
}

@Test
void adapterFailure_fallsBackToRawMapping() {
    CbrConfig config = CbrConfig.builder()
        .featureExtractor(ctx -> Map.of("f1", "v1"))
        .domain("test").build();
    CaseDefinition def = buildDefinition(config);
    PlanTrace pt = new PlanTrace("b1", "c1", "w1", "SUCCESS", 0, Map.of());
    PlanCbrCase planCase = new PlanCbrCase(
        "problem1", "solution1", "COMPLETED", 0.9,
        Map.of("f1", FeatureValue.string("v1")), List.of(pt));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(planCase, 0.8)));

    planAdapter.setResult(null);
    // Force adapter to throw
    service = new CbrRetrievalService(jqEvaluator, cbrStore, new PlanAdapter() {
        @Override
        public AdaptedPlan adapt(String caseType, ScoredCbrCase<PlanCbrCase> retrieved,
                                 Map<String, FeatureValue> currentFeatures) {
            throw new RuntimeException("adapter explosion");
        }
    });

    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();

    assertEquals(1, result.size());
    assertEquals("b1", result.get(0).planTrace().get(0).bindingName());
    assertNull(result.get(0).planTrace().get(0).adaptationAction());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
/opt/homebrew/bin/mvn test -pl runtime -Dtest="CbrRetrievalServiceTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: compilation failure — 3-arg `CbrRetrievalService` constructor does not exist.

- [ ] **Step 3: Implement CbrRetrievalService changes**

Three changes to `CbrRetrievalService.java`:

**3a. Add `PlanAdapter` field and update constructors:**

Add field:
```java
private final PlanAdapter planAdapter;
```

Update CDI constructor to inject `PlanAdapter`:
```java
@Inject
public CbrRetrievalService(
    JQEvaluator jqEvaluator,
    CbrCaseMemoryStore cbrStore,
    PlanAdapter planAdapter,
    @All Instance<CbrCaseTypeRegistration> registrations) {
  this.jqEvaluator = jqEvaluator;
  this.cbrStore = cbrStore;
  this.planAdapter = planAdapter;
  this.typeMap = buildTypeMap(registrations);
}
```

Update test constructor:
```java
CbrRetrievalService(JQEvaluator jqEvaluator, CbrCaseMemoryStore cbrStore, PlanAdapter planAdapter) {
  this.jqEvaluator = jqEvaluator;
  this.cbrStore = cbrStore;
  this.planAdapter = planAdapter;
  this.typeMap = Map.copyOf(BUILT_IN_TYPES);
}
```

Add imports:
```java
import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.PlanAdapter;
```

**3b. Thread `caseType` and `features` through `mapResults()`:**

Change the `.map(this::mapResults)` call in `retrieveInternal()` to pass `caseType` and `features`:

```java
.map(scoredCases -> mapResults(scoredCases, caseType, features))
```

Update `mapResults` signature:
```java
private <C extends CbrCase> List<RetrievedExperience> mapResults(
    List<ScoredCbrCase<C>> scoredCases, String caseType, Map<String, FeatureValue> features) {
  return scoredCases.stream().map(s -> mapScoredCase(s, caseType, features)).toList();
}
```

Update `mapScoredCase` to include adaptation:
```java
@SuppressWarnings("unchecked")
private <C extends CbrCase> RetrievedExperience mapScoredCase(
    ScoredCbrCase<C> scored, String caseType, Map<String, FeatureValue> features) {
  CbrCase c = scored.cbrCase();
  List<ExperiencePlanStep> trace;
  if (c instanceof PlanCbrCase) {
    trace = adaptAndMapPlanTrace((ScoredCbrCase<PlanCbrCase>) scored, caseType, features);
  } else {
    trace = List.of();
  }
  return new RetrievedExperience(
      c.problem(), c.solution(), c.outcome(), c.confidence(),
      scored.score(), new LinkedHashMap<>(c.features()), trace,
      scored.featureSimilarities());
}
```

**3c. Add the `adaptAndMapPlanTrace` method:**

```java
private List<ExperiencePlanStep> adaptAndMapPlanTrace(
    ScoredCbrCase<PlanCbrCase> scored, String caseType, Map<String, FeatureValue> features) {
  try {
    AdaptedPlan adapted = planAdapter.adapt(caseType, scored, features);
    return adapted.steps().stream()
        .filter(s -> s.action() != AdaptationAction.REMOVED)
        .map(s -> new ExperiencePlanStep(
            s.bindingName(), s.capabilityName(), s.workerName(),
            s.stepOutcome(), s.priority(), s.parameters(),
            s.action().name(), s.reason()))
        .toList();
  } catch (Exception e) {
    LOG.warnf(e, "PlanAdapter.adapt() failed — falling back to raw plan trace");
    return mapPlanTrace(scored.cbrCase().planTrace());
  }
}
```

Remove the now-unused direct `instanceof PlanCbrCase` check from the old `mapScoredCase` — it's replaced by the new one.

- [ ] **Step 4: Run CbrRetrievalServiceTest to verify all tests pass**

```bash
/opt/homebrew/bin/mvn test -pl runtime -Dtest="CbrRetrievalServiceTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: all tests PASS.

- [ ] **Step 5: Run CbrRetrievalCachingTest to verify no regression**

```bash
/opt/homebrew/bin/mvn test -pl runtime -Dtest="CbrRetrievalCachingTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: FAIL — the 2-arg test constructor no longer exists. Update `CbrRetrievalCachingTest.setUp()` to pass a `NoOpPlanAdapter` instance:

```java
import io.casehub.neocortex.memory.cbr.runtime.NoOpPlanAdapter;

// In setUp():
service = new CbrRetrievalService(jqEvaluator, cbrStore, new NoOpPlanAdapter());
```

Re-run:
```bash
/opt/homebrew/bin/mvn test -pl runtime -Dtest="CbrRetrievalCachingTest" -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: all tests PASS.

- [ ] **Step 6: Run full test suite to check for regressions**

```bash
TESTCONTAINERS_RYUK_DISABLED=true /opt/homebrew/bin/mvn install -DskipTests -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
TESTCONTAINERS_RYUK_DISABLED=true /opt/homebrew/bin/mvn test -pl api,runtime -f /Users/mdproctor/claude/casehub/engine/pom.xml -q
```

Expected: all tests PASS.

- [ ] **Step 7: Run ide_diagnostics on changed files**

Verify no IDE-level errors or warnings on:
- `CbrRetrievalService.java`
- `ExperiencePlanStep.java`
- `ExperienceAnalyser.java`

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/engine add runtime/src/main/java/io/casehub/engine/internal/routing/CbrRetrievalService.java runtime/src/test/java/io/casehub/engine/internal/routing/CbrRetrievalServiceTest.java runtime/src/test/java/io/casehub/engine/internal/routing/CbrRetrievalCachingTest.java
git -C /Users/mdproctor/claude/casehub/engine commit -m "feat(#738): wire PlanAdapter into CbrRetrievalService pipeline

CbrRetrievalService injects PlanAdapter (blocking, same pattern as
CbrCaseMemoryStore). For PlanCbrCase results, calls adapt() inside
mapScoredCase() — no separate pipeline stage. REMOVED steps filtered.
Adapter failure falls back to raw plan trace mapping.

Closes #738"
```
