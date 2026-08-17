# Annotation Capability Matrix

Maps every annotation capability to the example that demonstrates it and the test that verifies it.

## Examples

| Example | Domain | Module | Planning Mode |
|---------|--------|--------|---------------|
| **Customer Onboarding** | Banking — new account opening | `examples/simple-case-annotated` | EXPLICIT |
| **Contract Review** | Legal — contract risk analysis | `examples/goap-case-annotated` | GOAP |

## Capability → Example Matrix

| Capability | Annotation | Customer Onboarding | Contract Review | Deployment Tests |
|-----------|------------|:-------------------:|:---------------:|:----------------:|
| Case declaration | `@Case(namespace, name, version)` | ✓ | ✓ | EngineAnnotationsProcessorTest |
| Title and summary | `@Case(title, summary)` | ✓ | ✓ | — |
| GOAP planning mode | `@Case(planning = PlanningMode.GOAP)` | — | ✓ | EngineAnnotationsProcessorTest |
| Worker declaration | `@Worker(capability)` | ✓ | ✓ | EngineAnnotationsProcessorTest |
| Worker name override | `@Worker(value = "name")` | — | — | AnnotationFeaturesTest |
| Worker cost/benefit | `@Worker(cost, benefit)` | — | ✓ | AnnotationFeaturesTest |
| Worker description | `@Worker(description)` | ✓ | ✓ | — |
| Context-change trigger | `@Bind(contextChange)` | ✓ | — | EngineAnnotationsProcessorTest |
| Cron trigger | `@Bind(cron)` | — | — | AnnotationFeaturesTest |
| Repeatable @Bind | Multiple `@Bind` on one method | — | — | AnnotationFeaturesTest |
| When guard | `@Bind(when)` | ✓ | — | AnnotationFeaturesTest |
| GOAP auto-binding | Implicit `ContextChangeTrigger("true")` | — | ✓ | EngineAnnotationsProcessorTest |
| Goal declaration | `@Goal(value, condition)` | ✓ | ✓ | EngineAnnotationsProcessorTest |
| Milestone | `@Milestone(name, completionCriteria, entryCriteria)` | ✓ | — | EngineAnnotationsProcessorTest |
| Effect key override | `@Effect("key")` | — | ✓ | AnnotationFeaturesTest |
| Soft dependency | `@SoftDependency` | — | ✓ | AnnotationFeaturesTest |
| Parameter exclusion | `@Param("key")` | — | ✓ | AnnotationFeaturesTest |
| Completion wiring | `@Completion` → `GoalExpression` | — | ✓ | CompletionWiringTest |
| Customize escape hatch | `@Customize` → `CaseDefinition.Builder` | ✓ | — | CustomizeTest |
| SystemPrompt (AI worker) | `@SystemPrompt("prompt")` | — | — | SystemPromptTest |
| Standalone capability | `@Capability` (without `@Worker`) | — | — | — |
| GOAP type inference | Parameter types → preconditions, return type → effects | — | ✓ | EngineAnnotationsProcessorTest |
| Goal-to-effect mapping | `GoalConditionParser` → `goalToEffectKeys` | — | ✓ | GoapAnnotatedCaseTest |
| Gizmo subclass | `_CaseHubImpl` bytecode generation | ✓ | ✓ | GizmoSubclassTest |
| Default method invocation | `AnnotationWorkerFunction` → reflection | ✓ | ✓ | AnnotationWorkerFunctionTest |
| Build-time validation | Trigger exclusivity, duplicates, `-parameters` | — | — | ValidationErrorTest |

## Coverage Summary

| Category | Total | In Examples | In Deployment Tests Only |
|----------|-------|-------------|--------------------------|
| Annotations | 15 | 12 | 3 (`@SystemPrompt`, `@Capability` standalone, `@Worker(value)`) |
| Inference | 3 | 3 | — |
| Build pipeline | 3 | 2 | 1 (validation) |
| **Total** | **21** | **17** | **4** |

## How to Run

```bash
# All examples
mvn test -pl examples/simple-case-annotated,examples/goap-case-annotated

# All deployment tests (includes build-time validation, Gizmo, etc.)
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl annotations/deployment

# Everything
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl annotations/runtime,annotations/deployment,examples/simple-case-annotated,examples/goap-case-annotated
```
