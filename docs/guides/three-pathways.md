# Defining Cases — YAML, Java, and TypeScript

CaseHub case definitions can be authored in YAML or Java. Both produce the same `CaseDefinition` at runtime. A TypeScript pathway is planned but not yet available.

The Java pathway has two layers: **annotations** for the common case (workers, bindings, goals expressed declaratively on an interface) and the **fluent DSL** for anything annotations cannot reach (humanTask targets, sub-case bindings, conditional logic). These layers blend — `@Customize` drops from annotations into the DSL within the same class.

---

## Choosing a Pathway

| Factor | YAML | Java |
|---|---|---|
| **Author** | Ops, analysts, non-Java teams | Java developers |
| **Code required** | No | Yes |
| **Worker functions** | External (`do:`, `a2a:`, `mcp:`) | In-process (default methods) or external |
| **AI agents** | `agent:` block on worker | `@SystemPrompt` or `Agent.builder()` |
| **Primary surface** | YAML file | `@Case` interface (annotations) |
| **Escape hatch** | `YamlCaseHub.augment()` → Java | `@Customize` → fluent DSL |
| **Hot reload** | File change (with overlay support) | Recompile |
| **Best for** | Deployment configs, non-Java teams, CI/CD | Typed worker I/O, in-process logic |

**Decision tree:**

1. Does your team write Java? **No** → YAML
2. Do workers need typed parameters and return values? **Yes** → Java (annotations)
3. Do bindings need humanTask targets, sub-cases, or conditional logic? **Yes** → Java (annotations + `@Customize` into DSL)
4. Otherwise → YAML (simplest path)

**TypeScript:** A TypeScript pathway targeting Node.js/Deno runtimes is planned. The YAML pathway will work unchanged; TypeScript will provide its own annotation and builder equivalents.

---

## Side-by-Side: Customer Onboarding

The same choreography case — identity verification, compliance screening, account provisioning — in all three pathways. Each binding fires independently when its context conditions are met.

Full examples: [`examples/yaml/choreography-onboarding.yaml`](../../examples/yaml/choreography-onboarding.yaml), [`examples/choreography-annotated/`](../../examples/choreography-annotated/), [`examples/choreography-dsl/`](../../examples/choreography-dsl/)

### YAML

```yaml
dsl: "1.0.0"
namespace: banking
name: customer-onboarding
version: "1.0.0"

spec:
  capabilities:
    - name: verifyIdentity
      inputProjection: "{ application: .application }"
      outputProjection: "{ identityResult: { verified: .verified, referenceId: .referenceId } }"
    # ... kycScreening, provisionAccount

  workers:
    - name: identity-verifier
      capabilities: [verifyIdentity]
      do:
        - verify:
            call: http
            with:
              method: POST
              endpoint:
                uri: https://identity-service.internal/api/verify

  bindings:
    - name: verify-on-application
      capability: verifyIdentity
      on:
        contextChange:
          filter: '.application != null and .identityResult == null'

    - name: screen-after-verified
      capability: kycScreening
      on:
        contextChange:
          filter: '.identityResult != null and .complianceResult == null'
      when: '.identityResult.verified == true'

  goals:
    - name: accountOpened
      condition: '.account != null and .account.status == "ACTIVE"'
      kind: success

  completion:
    success:
      allOf: [accountOpened]
```

Workers use Serverless Workflow `do:` blocks for external HTTP dispatch. No Java compilation needed — drop the file into the classpath and register via `YamlCaseHub`.

### Java — Annotations Layer

The default Java entry point. Workers are default methods; bindings are `@Bind` annotations; capabilities are inferred from types.

```java
@Case(namespace = "banking", name = "CustomerOnboarding", version = "1.0.0")
public interface SimpleAnnotatedCase {

  @Worker(capability = "verifyIdentity")
  @Bind(contextChange = ".application != null")
  default IdentityResult verifyIdentity(String application) {
    return new IdentityResult(true, "ID-" + application.hashCode());
  }

  @Worker(value = "kycScreening")
  @Bind(contextChange = ".identityResult != null",
        when = ".identityResult.verified == true")
  @Bind(cron = "0 0 * * * ?")
  default ComplianceResult checkCompliance(IdentityResult identityResult) {
    return new ComplianceResult("PASS", identityResult.referenceId());
  }

  // ... provisionAccount, milestones, goals

  record IdentityResult(boolean verified, String referenceId) {}
  record ComplianceResult(String status, String referenceId) {}
}
```

Worker functions are default methods on the interface. Parameter and return types are automatically bridged — the engine deserialises context into the parameter type and serialises the return value back. `@Bind` is repeatable, so a single worker can have multiple trigger bindings (the KYC worker above fires on both context change and a cron schedule).

Capabilities are inferred from `@Worker.capability` (or `@Worker.value`). Input/output projections are inferred from the method signature types.

### Java — DSL Layer

The underlying builder that annotations compile to. Use directly when annotations cannot express what you need, or via `@Customize` to blend both layers in one class.

```java
public static CaseDefinition define() {
  Capability verifyIdentity = Capability.of("verifyIdentity",
      "{ application: .application }",
      "{ identityResult: { verified: .verified, referenceId: .referenceId } }");
  // ... kycScreening, provisionAccount

  return CaseDefinition.builder()
      .namespace("banking").name("customer-onboarding").version("1.0.0")
      .capabilities(verifyIdentity, kycScreening, provisionAccount)
      .workers(
          Worker.builder().name("identity-verifier")
              .capabilityName("verifyIdentity").noFunction().build(),
          // ...
      )
      .bindings(
          Binding.builder().name("verify-on-application")
              .capability(verifyIdentity)
              .on(new ContextChangeTrigger(
                  ".application != null and .identityResult == null"))
              .build(),
          // ...
      )
      .completion(GoalExpression.goal("accountOpened"),
                  GoalExpression.goal("complianceFailed"))
      .build();
}
```

The DSL is explicit — every field is named, every relationship is visible. Workers declared with `.noFunction()` rely on external dispatch (Qhorus channels, A2A, MCP). Workers with in-process functions use the typed `Worker.builder().<T>fn().apply((input, scope) -> ...)` chain.

---

## Blending and Bridging

Within Java, annotations and DSL blend naturally. Between YAML and Java, a bridge method adds programmatic behaviour to declarative structure.

### Within Java: `@Customize`

Annotations handle workers, bindings, goals, and milestones. When a binding needs a humanTask target, sub-case, or conditional logic, `@Customize` drops into the DSL without leaving the same class:

```java
@Case(namespace = "finance", name = "LoanApproval", version = "1.0.0")
public interface LoanApprovalCase {

  @Worker(capability = "assessCredit")
  @Bind(contextChange = ".application != null")
  default Map<String, Object> assessCredit(Map<String, Object> input) { /* ... */ }

  @Customize
  static void customize(CaseDefinition.Builder builder) {
    builder.bindings(
        Binding.builder().name("officer-approval")
            .judgment(JudgmentTarget.builder()
                .prompt("Review loan application")
                .candidateGroups(StaticSetStrategy.of("loan-officers"))
                .build())
            .on(new ContextChangeTrigger(".riskAssessment != null"))
            .build());
  }
}
```

The `@Customize` method receives the builder after all annotation processing is complete. It can add bindings, workers, goals, milestones — anything the DSL supports.

### Between Pathways: `YamlCaseHub.augment()`

YAML defines the case structure; Java adds workers that need in-process logic:

```java
@ApplicationScoped
public class OnboardingCase extends YamlCaseHub {

  public OnboardingCase() {
    super("banking/customer-onboarding.yaml");
  }

  @Override
  protected void augment(CaseDefinition definition) {
    definition.getWorkers().add(
        Worker.builder().name("fraud-scorer")
            .capabilityName("scoreFraud")
            .<Map<String, Object>>fn()
            .apply((input, scope) -> Map.of("fraudScore", computeScore(input)))
            .build());
  }
}
```

`augment()` runs once, after YAML loading and overlay merging. CDI-injected fields are available. The YAML file remains the source of truth for structure; Java adds the behaviour that YAML cannot express.

### When to use each

| Situation | Approach |
|---|---|
| Java case needs a humanTask, sub-case, or conditional binding | `@Customize` (annotations + DSL in one class) |
| YAML case needs JVM-resident worker logic | `YamlCaseHub.augment()` |
| Non-Java team owns the case structure, Java team owns the workers | YAML + `augment()` |
| Entire definition is conditional or computed at startup | Pure DSL (no annotations) |

---

## YAML DSL Reference

### Top-Level Fields

| Field | Required | Description |
|---|---|---|
| `dsl` | yes | Schema version. Currently `"1.0.0"` |
| `namespace` | yes | Logical grouping (e.g. `banking`, `insurance`) |
| `name` | yes | Case definition name, unique within namespace |
| `version` | yes | Semantic version (`1.0.0`) |
| `title` | no | Human-readable display title |
| `summary` | no | One-line description |
| `types` | no | Hierarchical classification paths (e.g. `banking/onboarding`) |
| `labels` | no | Arbitrary tags (e.g. `example/choreography`) |

### `spec:` Block

The `spec:` block contains the case definition body. All fields below are nested under `spec:`.

#### `capabilities`

Declare what workers can do — named competences with input/output contracts.

```yaml
capabilities:
  - name: verifyIdentity
    description: "Verifies customer identity documents"
    inputProjection: "{ application: .application }"
    outputProjection: "{ identityResult: .result }"
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Capability identifier — referenced by bindings and workers |
| `description` | no | Human-readable description |
| `inputProjection` | no | JQ expression transforming context → worker input |
| `outputProjection` | no | JQ expression transforming worker output → context update |

#### `workers`

Autonomous participants that observe context, make decisions, and perform work.

```yaml
workers:
  - name: identity-verifier
    capabilities: [verifyIdentity]
    do:
      - verify:
          call: http
          with:
            method: POST
            endpoint:
              uri: https://identity-service.internal/api/verify
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Worker identifier |
| `capabilities` | yes | List of capability names this worker handles |
| `description` | no | Human-readable description |
| `do` | no | Serverless Workflow steps for external dispatch |
| `agent` | no | AI agent configuration (`model`, `modelName`, `systemPrompt`) |
| `a2a` | no | Remote A2A agent (`endpoint`, `skill`, `streaming`, `auth`) |
| `mcp` | no | MCP tool server (`command` for stdio, `url` for HTTP) |
| `executionPolicy` | no | Retry and timeout configuration |
| `cost` | no | Static cost for GOAP planning (default: 1.0) |
| `effect` | no | Boolean state effects for GOAP planning |
| `contextType` | no | Typed context bridge class name |
| `definitionRef` | no | External definition reference (`workflows/research.yaml` or `#inline-name`) |

Worker dispatch types (`do`, `agent`, `a2a`, `mcp`) are mutually exclusive. A worker with none of these is externally dispatched via Qhorus channels.

#### `bindings`

Connect trigger conditions to worker capabilities. Each binding has exactly one target type.

```yaml
bindings:
  - name: verify-on-application
    capability: verifyIdentity
    on:
      contextChange:
        filter: '.application != null and .identityResult == null'
    when: '.application.type == "premium"'
```

**Target types** (mutually exclusive):

| Target | Description |
|---|---|
| `capability` | Routes to a worker by capability match |
| `subCase` | Spawns a child case |
| `humanTask` | Creates a WorkItem in casehub-work |
| `signal` | Writes a static payload to case context (no worker dispatch) |

**Trigger types** (under `on:`):

| Trigger | Description |
|---|---|
| `contextChange: { filter: "<jq>" }` | Fires when context matches the filter expression |
| `schedule: { cron: "<expr>" }` | Fires on a Quartz cron schedule |
| `schedule: { every: "<duration>" }` | Fires once after an ISO-8601 duration |
| `cloudEvent: { type: "<type>" }` | Fires on matching CloudEvents |
| `scopeActivated: {}` | Fires when the owning compound scope becomes active |

**Common binding fields:**

| Field | Description |
|---|---|
| `name` | Binding identifier (unique within definition) |
| `when` | Guard condition — evaluated after trigger fires |
| `outcomePolicy` | How to handle worker decline/failure/expiration (`onDecline`, `onFailure`, `onExpired`: `REROUTE` or `FAULT`) |
| `producedKeys` | Context keys this binding is expected to produce |
| `contextWrite` | Static values merged into context before dispatch |
| `lifecycleScope` | `BINDING` (single dispatch), `COMPOUND`, or `CASE` |
| `participation` | `PARTICIPANT` (blocks completion) or `COMPANION` (sidecar) |
| `executionMode` | `TRANSIENT`, `PERSISTENT`, or `REINVOKED` |

#### `goals`

Desired end-states expressed as predicates over the case context.

```yaml
goals:
  - name: accountOpened
    condition: '.account != null and .account.status == "ACTIVE"'
    kind: success

  - name: complianceFailed
    condition: '.complianceResult.status == "FAIL"'
    kind: failure
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Goal identifier |
| `condition` | yes | JQ predicate — goal reached when true |
| `kind` | no | `success` (default), `failure`, or custom kind with explicit terminal status |
| `description` | no | Human-readable description |

#### `milestones`

Observable progress markers — not completion conditions, but checkpoints that track where a case is in its lifecycle.

```yaml
milestones:
  - name: identityVerified
    entryCriteria: '.application != null'
    condition: '.identityResult.verified == true'
    slaDuration: PT24H
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Milestone identifier |
| `condition` | yes | JQ predicate — milestone completes when true after activation |
| `entryCriteria` | no | JQ predicate — milestone activates when true |
| `description` | no | Human-readable description |
| `slaDuration` | no | ISO-8601 duration — fires SLA timeout when exceeded |
| `slaStartFrom` | no | `MILESTONE_ACTIVATED` (default), `CASE_CREATED`, `PREVIOUS_MILESTONE_COMPLETED`, `EVENT_OCCURRED` |

#### `completion`

Defines when a case reaches a terminal state. Maps goal kinds to goal expressions.

```yaml
completion:
  success:
    allOf: [accountOpened]
  failure:
    anyOf: [complianceFailed, timeoutExpired]
```

Goal expressions compose recursively — `allOf` and `anyOf` can nest:

```yaml
completion:
  success:
    allOf:
      - primaryGoal
      - anyOf: [secondaryA, secondaryB]
```

Alternative: `doneWhen` for predicate-based completion without goals:

```yaml
completion:
  doneWhen: '.result != null'
```

---

## Advanced YAML Features

Features beyond the core case structure — available in YAML but less commonly used.

| Feature | YAML field | Description |
|---|---|---|
| GOAP planning | `goapActions`, `decompositionStrategy: goap` | Automated plan generation from precondition/effect declarations |
| Compounds | `compounds` | Scoped binding groups with entry/exit conditions and completion semantics |
| Plan adaptation | `adaptation: adaptive` | Re-plans when step outputs change the optimal path |
| Recovery policy | `recoveryPolicy` | Multi-level failure escalation (transient → reasoning → fundamental) |
| CBR retrieval | `cbr` | Case-Based Reasoning from historical case outcomes |
| Label rules | `labelRules` | Dynamic case labelling from context predicates |
| Inbound mappings | `inboundMappings` | Bridge external connector messages to typed case signals |
| Authorization | `authorization` | ACL grants created when a case starts |
| Expression override | `{ mvel: "expr" }` | Per-expression language override (default is JQ) |

Each feature has its own section in the [consumer guide](consumer-guide.md) and corresponding examples in the `examples/` directory.
