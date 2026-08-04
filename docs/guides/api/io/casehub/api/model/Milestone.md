# io.casehub.api.model.Milestone

**Package:** `io.casehub.api.model`

**Kind:** `class`

A named waypoint that a case passes through on its way to a `Goal`.

<p>Milestones and goals answer different questions:

<ul>
  <li><b>Milestones</b> — where are we? A milestone marks a point of progress. It has no
      success/failure polarity; it is a neutral checkpoint that the case either has or has not
      reached. You <em>pass</em> milestones.
  <li><b>Goals</b> — what outcome are we trying to achieve? A goal carries `GoalKind`
      (SUCCESS or FAILURE) and drives case completion. You <em>achieve</em> goals.
</ul>

<p>Example — loan application case:

<pre>`// Milestones: intermediate waypoints
Milestone.builder().name("documents-received").completionCriteria(".docsUploaded == true").build()
Milestone.builder().name("credit-check-complete").completionCriteria(".creditScore != null").build()
Milestone.builder().name("underwriting-done").completionCriteria(".underwritingStatus == \"complete\"").build()

// Goals: terminal outcomes
Goal.builder().name("loan-approved").condition(".decision == \"approved\"").kind(GoalKind.SUCCESS).build()
Goal.builder().name("loan-rejected").condition(".decision == \"rejected\"").kind(GoalKind.FAILURE).build()`</pre>

<h3>Lifecycle States</h3>

<p>Milestones progress through lifecycle states tracked via `MilestoneLifecycleStatus`:

<ul>
  <li><b>PENDING</b> — waiting for `entryCriteria` to become true
  <li><b>ACTIVE</b> — `entryCriteria` met, working toward `completionCriteria`
  <li><b>COMPLETED</b> — `completionCriteria` met
</ul>

<h3>SLA Tracking</h3>

<p>Optional `slaDuration` enables SLA deadline tracking via `SlaStatus`:

<ul>
  <li><b>NOT_STARTED</b> — milestone not yet activated (PENDING)
  <li><b>ON_TRACK</b> — activated, within SLA deadline
  <li><b>BREACHED</b> — SLA deadline passed
</ul>

<p>SLA status is orthogonal to lifecycle: a milestone can be COMPLETED + BREACHED (late
completion).

<h3>Evaluation and Lifecycle Management</h3>

<p>The engine evaluates milestones via `MilestoneLifecycleManager`, which owns the full
lifecycle from PENDING to COMPLETED. Evaluation follows these steps:

<ol>
  <li><b>PENDING → ACTIVE</b> — fires when `entryCriteria` becomes true; publishes `MilestoneActivatedEvent`; starts SLA tracking if `slaDuration` is set
  <li><b>ACTIVE → COMPLETED</b> — fires when `completionCriteria` becomes true; publishes
      `MilestoneCompletedEvent`; stops SLA tracking
</ol>

<p>Milestone state is written to CaseContext at `milestones.<name>.*` and can be referenced
by any `io.casehub.platform.api.expression.ExpressionEvaluator` — compound conditions,
binding triggers, goal conditions. The `Milestone` class itself is immutable — the
lifecycle manager owns the state transitions.

## Fields

### `DEFAULT_ENTRY_CRITERIA` (`ExpressionEvaluator`)

### `completionCriteria` (`ExpressionEvaluator`)

### `description` (`java.lang.String`)

### `entryCriteria` (`ExpressionEvaluator`)

### `name` (`java.lang.String`)

### `slaDuration` (`java.time.Duration`)

### `slaStartFrom` (`io.casehub.api.model.SlaStartFrom`)

## Constructors

### `public Milestone(java.lang.String name, ExpressionEvaluator entryCriteria, ExpressionEvaluator completionCriteria, java.time.Duration slaDuration, io.casehub.api.model.SlaStartFrom slaStartFrom)`

#### Parameters

- `name` (`java.lang.String`)
- `entryCriteria` (`ExpressionEvaluator`)
- `completionCriteria` (`ExpressionEvaluator`)
- `slaDuration` (`java.time.Duration`)
- `slaStartFrom` (`io.casehub.api.model.SlaStartFrom`)

## Methods

### `public static io.casehub.api.model.Milestone.Builder builder()`

### `public boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public ExpressionEvaluator getCompletionCriteria()`

### `public java.lang.String getDescription()`

### `public ExpressionEvaluator getEntryCriteria()`

### `public java.lang.String getName()`

### `public java.time.Duration getSlaDuration()`

### `public io.casehub.api.model.SlaStartFrom getSlaStartFrom()`

### `public int hashCode()`

### `public void setDescription(java.lang.String description)`

#### Parameters

- `description` (`java.lang.String`)
