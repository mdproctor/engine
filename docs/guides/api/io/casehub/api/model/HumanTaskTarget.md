# io.casehub.api.model.HumanTaskTarget

**Package:** `io.casehub.api.model`

**Kind:** `class`

Binding target that routes to a human task in casehub-work.

<p>Two entry points:

<ul>
  <li>`.template(String)` — references a reusable `WorkItemTemplate` by ID
  <li>`.inline()` — self-contained one-off task definition
</ul>

<p>Both modes support `inputMapping` (context → task payload) and `outputMapping`
(task resolution → context update). Mapping strings are treated as JQ expressions.

## Fields

### `candidateGroups` (`io.casehub.api.spi.routing.CandidateSetSpec`)

### `candidateUsers` (`io.casehub.api.spi.routing.CandidateSetSpec`)

### `claimDeadlineHours` (`java.lang.Integer`)

### `expiresAtExpression` (`ExpressionEvaluator`)

### `expiresIn` (`java.time.Duration`)

### `expiresInExpression` (`ExpressionEvaluator`)

### `inputMapping` (`ExpressionEvaluator`)

### `outcomes` (`java.util.Set<java.lang.String>`)

### `outputMapping` (`ExpressionEvaluator`)

### `payloadType` (`java.lang.Class<?>`)

### `priority` (`java.lang.String`)

### `resolutionType` (`java.lang.Class<?>`)

### `scope` (`java.lang.String`)

### `scopeExpression` (`ExpressionEvaluator`)

### `templateRef` (`java.lang.String`)

### `title` (`java.lang.String`)

### `titleExpression` (`ExpressionEvaluator`)

## Constructors

### `private HumanTaskTarget(io.casehub.api.model.HumanTaskTarget.Builder builder)`

#### Parameters

- `builder` (`io.casehub.api.model.HumanTaskTarget.Builder`)

## Methods

### `public io.casehub.api.spi.routing.CandidateSetSpec candidateGroups()`

### `public io.casehub.api.spi.routing.CandidateSetSpec candidateUsers()`

### `public java.lang.Integer claimDeadlineHours()`

Business hours allowed to claim this WorkItem before escalation. Null means unset.

### `public ExpressionEvaluator expiresAtExpression()`

JQ expression evaluated against case context WORKING layer to produce an absolute deadline
Instant.

### `public java.time.Duration expiresIn()`

### `public ExpressionEvaluator expiresInExpression()`

### `public static io.casehub.api.model.HumanTaskTarget.Builder inline()`

Entry point for inline mode — task is fully self-contained, no template lookup required.

### `public ExpressionEvaluator inputMapping()`

### `public boolean isTemplateMode()`

### `public java.util.Set<java.lang.String> outcomes()`

### `public ExpressionEvaluator outputMapping()`

### `public java.lang.Class<?> payloadType()`

### `public java.lang.String priority()`

### `public java.lang.Class<?> resolutionType()`

### `public java.lang.String scope()`

Hierarchical scope path for SLA preference resolution (e.g. `"casehubio/devtown/pr-review"`). Null means unscoped; the work expiry service falls back to
root scope.

### `public ExpressionEvaluator scopeExpression()`

### `public static io.casehub.api.model.HumanTaskTarget.Builder template(java.lang.String templateRef)`

Entry point for template mode — references a `WorkItemTemplate` in casehub-work by ref
(UUID or name, resolved by `HumanTaskScheduleHandler`).

#### Parameters

- `templateRef` (`java.lang.String`)

### `public java.lang.String templateRef()`

### `public java.lang.String title()`

### `public ExpressionEvaluator titleExpression()`
