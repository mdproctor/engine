# io.casehub.api.model.Goal

**Package:** `io.casehub.api.model`

**Kind:** `class`

A named outcome that a case is trying to achieve, with success or failure polarity.

<p>Milestones and goals answer different questions:

<ul>
  <li><b>Goals</b> — what outcome are we trying to achieve? A goal carries a kind label (e.g.
      "success", "failure", or domain-specific) and drives case completion via `io.casehub.api.model.GoalBasedCompletion`. You <em>achieve</em> goals.
  <li><b>Milestones</b> — where are we? A milestone marks a neutral point of progress on the way
      to a goal. It has no success/failure polarity. You <em>pass</em> milestones.
</ul>

<p>Example — loan application case:

<pre>`// Milestones: intermediate waypoints (no polarity)
Milestone.builder().name("documents-received").completionCriteria(".docsUploaded == true").build()
Milestone.builder().name("credit-check-complete").completionCriteria(".creditScore != null").build()

// Goals: terminal outcomes (SUCCESS or FAILURE)
Goal.builder().name("loan-approved").condition(".decision == \"approved\"").kind(GoalKind.SUCCESS).build()
Goal.builder().name("loan-rejected").condition(".decision == \"rejected\"").kind(GoalKind.FAILURE).build()`</pre>

<p>When a goal's condition becomes true, a `GoalReachedEvent` is published and recorded in
the `io.casehub.engine.internal.history.EventLog`. If the goal is referenced by a `io.casehub.api.model.GoalBasedCompletion`, the engine evaluates whether the case should
transition to COMPLETED or FAILED. Goals are always terminal — use `Milestone` for
non-terminal checkpoints.

## Fields

### `condition` (`ExpressionEvaluator`)

### `description` (`java.lang.String`)

### `kind` (`java.lang.String`)

### `name` (`java.lang.String`)

## Constructors

### `public Goal(java.lang.String name, ExpressionEvaluator condition, java.lang.String kind)`

#### Parameters

- `name` (`java.lang.String`)
- `condition` (`ExpressionEvaluator`)
- `kind` (`java.lang.String`)

## Methods

### `public static io.casehub.api.model.Goal.Builder builder()`

### `public boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public ExpressionEvaluator getCondition()`

### `public java.lang.String getDescription()`

### `public java.lang.String getKind()`

### `public java.lang.String getName()`

### `public int hashCode()`

### `public void setDescription(java.lang.String description)`

#### Parameters

- `description` (`java.lang.String`)
