# CaseDefinition.yaml Schema Verification and Update

**Issue:** engine#847
**Date:** 2026-08-02

## Problem

The CaseDefinition.yaml JSON Schema has drifted from the Java domain model. `unevaluatedProperties: true` on the root and spec silently accepts properties not declared in the schema. The YAML mapper reads many properties from raw JSON nodes that bypass the schema entirely — meaning the schema validates nothing for those fields. Several features documented in the schema (Worker sequence composition, typed output) are broken because WorkerMarshaller doesn't read them.

## Design Principle: Plugin Equality

No worker function type is privileged. `agent:` is a plugin, same as `do:` (flow), and future types (`mcp:`, `http:`, `script:`, `k8s:`). The schema must not structurally couple Worker to any specific function type. Same principle applies at the spec level — CBR config, routing strategies, and future plugin configurations are extension points.

## Changes

### 1. Schema — Add Missing Properties

Properties the mapper reads from rawNode but the schema doesn't declare:

**Top-level:**
- `context:` — object with `storeFactory` (string, strategy ID)
- `labelRules:` — array of `LabelRule` (new $def: name, when, actions)
- `inboundMappings:` — array of `InboundSignalMapping` (new $def: signal, connectorType, correlation, payload, correlationResolver)

**On CaseDefinitionSpec:**
- `routingSignalWeights:` — object, additionalProperties: number
- `agentRouting:` — string (strategy ID)
- `implementationRouting:` — string (strategy ID)
- `humanTaskRouting:` — string (strategy ID)
- `candidateMatching:` — string (strategy ID)

**On Capability:**
- `cognitiveDemand:` — object, additionalProperties: number (Jungian function weights)

**On Cbr:**
- `cbrType:` — string (CbrCase Java class discriminator)

**On Worker (hand-written class):**
- `contextType:` — string (fully qualified class name for typed input)
- `outputType:` — string (fully qualified class name for typed output)

### 2. Schema — Flatten Worker, Remove External $ref

Replace the `allOf` + `anyOf` + external serverless workflow `$ref` with a flat object.

**Before:**
```yaml
Worker:
    allOf:
      - type: object
        required: [name, capabilities]
        unevaluatedProperties: false
        properties: {name, description, capabilities, executionPolicy, sequence}
      - anyOf:
          - $ref: "https://raw.githubusercontent.com/.../workflow.yaml"
          - type: string
          - type: object
            required: [agent]
            properties:
              agent: { $ref: "#/$defs/Agent" }
```

**After:**
```yaml
Worker:
    type: object
    required: [name, capabilities]
    additionalProperties: true  # Extension point for pluggable function types
    properties:
      name: ...
      description: ...
      capabilities: ...
      executionPolicy: ...
      sequence: ...
      contextType: ...
      outputType: ...
```

`agent:` is NOT declared on Worker — it's a plugin-supplied block handled by `WorkerFunctionProviderRegistry`. The `additionalProperties: true` allows any plugin to add its own configuration block.

The `$defs` for Agent, AgentModel, and LLM provider models remain for code generation. They are referenced via the `_codegen` properties on CaseDefinitionSpec (renamed from `_force*` for clarity).

### 3. Schema — Strictness by Type Category

| Type | `unevaluatedProperties` | Category |
|------|------------------------|----------|
| Root (CaseHub) | `false` | Structural — all top-level properties are declared |
| CaseDefinitionSpec | `true` | Extension point — plugins may add config blocks |
| Worker | `additionalProperties: true` | Extension point — pluggable function types |
| Binding | `false` | Structural |
| Trigger, ContextChangeTrigger, CloudEventTrigger, ScheduleTrigger, ScopeActivatedTrigger | `false` | Structural |
| Milestone, Goal, GoalExpression, CaseCompletion | `false` | Structural |
| SubCase, HumanTask | `false` | Structural |
| Capability | `false` | Structural |
| Cbr | `false` | Structural |
| OutcomePolicy, ExecutionPolicy, RetryPolicy | `false` | Structural |
| Use, Authorization | `false` | Structural |
| Agent, AgentModel, all LLM models | `false` | Structural |

### 4. Schema — Rename `_force*` to `_codegen*`

The 7 `_force*` properties on CaseDefinitionSpec are renamed to `_codegen*` with improved documentation. These are a code-generation registry — they ensure jsonschema2pojo traverses types not directly reachable from the schema root (because Worker is hand-written via CasehubRuleFactory).

### 5. Mapper — Wire Missing Fields

Add to `CaseDefinitionYamlMapper.convertToApiModel()`:
- `def.setSummary(schema.getSummary())` — currently in schema but not read
- `agentRouting` — read from `rawNode.get("spec").get("agentRouting")`
- `implementationRouting` — same pattern
- `humanTaskRouting` — same pattern
- `candidateMatching` — same pattern

### 6. WorkerMarshaller — Fix Missing Reads

`WorkerMarshaller.Deserializer` doesn't read `sequence` or `outputType`, silently breaking these features from YAML.

Add reads for:
- `sequence` — `List<String>` from `root.get("sequence")`
- `outputType` — string from `root.get("outputType")`

### 7. Validation Test

Add `SchemaValidationTest` to the schema module that:
1. Loads CaseDefinition.yaml as a JSON Schema
2. Validates all example YAML files in `schema/src/main/resources/examples/`
3. Validates all test YAML files in `*/src/test/resources/casehub/`
4. Asserts that invalid YAML (extra properties on structural types) is rejected

## Files Changed

| File | Change |
|------|--------|
| `schema/src/main/resources/schema/CaseDefinition.yaml` | Add missing properties, flatten Worker, tighten structural types, rename _force* |
| `schema/src/main/java/io/casehub/model/Worker.java` | No change (already has all fields) |
| `schema/src/main/java/io/casehub/model/marshaller/WorkerMarshaller.java` | Add sequence, outputType reads |
| `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` | Wire summary, routing strategy IDs |
| `schema/src/test/java/io/casehub/model/SchemaValidationTest.java` | New — validates schema against all YAML files |

## Out of Scope

- TypeScript type generation from the schema (blocks-ui#103 Phase 0 — depends on this)
- Worker legacy fields (`inputSchema`/`outputSchema` on hand-written Worker) — deprecated, not removed
- Compound (PlanItemDefinition.Compound) YAML support — DSL-only, not YAML-expressible
- Plugin-specific schema validation (validating `agent:` block structure) — handled at runtime by providers
