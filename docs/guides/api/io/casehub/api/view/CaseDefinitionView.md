# io.casehub.api.view.CaseDefinitionView

**Package:** `io.casehub.api.view`

**Kind:** `record`

## Fields

### `bindings` (`java.util.List<io.casehub.api.view.BindingView>`)

### `capabilities` (`java.util.List<java.lang.String>`)

### `completion` (`io.casehub.api.view.CompletionView`)

### `goals` (`java.util.List<io.casehub.api.view.GoalView>`)

### `milestones` (`java.util.List<io.casehub.api.view.MilestoneView>`)

### `name` (`java.lang.String`)

### `namespace` (`java.lang.String`)

### `summary` (`java.lang.String`)

### `title` (`java.lang.String`)

### `version` (`java.lang.String`)

### `workers` (`java.util.List<io.casehub.api.view.WorkerView>`)

## Record Components

### `bindings` (`java.util.List<io.casehub.api.view.BindingView>`)

### `capabilities` (`java.util.List<java.lang.String>`)

### `completion` (`io.casehub.api.view.CompletionView`)

### `goals` (`java.util.List<io.casehub.api.view.GoalView>`)

### `milestones` (`java.util.List<io.casehub.api.view.MilestoneView>`)

### `name` (`java.lang.String`)

### `namespace` (`java.lang.String`)

### `summary` (`java.lang.String`)

### `title` (`java.lang.String`)

### `version` (`java.lang.String`)

### `workers` (`java.util.List<io.casehub.api.view.WorkerView>`)

## Constructors

### `public CaseDefinitionView(java.lang.String namespace, java.lang.String name, java.lang.String version, java.lang.String title, java.lang.String summary, java.util.List<java.lang.String> capabilities, java.util.List<io.casehub.api.view.WorkerView> workers, java.util.List<io.casehub.api.view.BindingView> bindings, java.util.List<io.casehub.api.view.MilestoneView> milestones, java.util.List<io.casehub.api.view.GoalView> goals, io.casehub.api.view.CompletionView completion)`

#### Parameters

- `namespace` (`java.lang.String`)
- `name` (`java.lang.String`)
- `version` (`java.lang.String`)
- `title` (`java.lang.String`)
- `summary` (`java.lang.String`)
- `capabilities` (`java.util.List<java.lang.String>`)
- `workers` (`java.util.List<io.casehub.api.view.WorkerView>`)
- `bindings` (`java.util.List<io.casehub.api.view.BindingView>`)
- `milestones` (`java.util.List<io.casehub.api.view.MilestoneView>`)
- `goals` (`java.util.List<io.casehub.api.view.GoalView>`)
- `completion` (`io.casehub.api.view.CompletionView`)

## Methods

### `public java.util.List<io.casehub.api.view.BindingView> bindings()`

### `public java.util.List<java.lang.String> capabilities()`

### `public io.casehub.api.view.CompletionView completion()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.view.GoalView> goals()`

### `public final int hashCode()`

### `public java.util.List<io.casehub.api.view.MilestoneView> milestones()`

### `public java.lang.String name()`

### `public java.lang.String namespace()`

### `public java.lang.String summary()`

### `public java.lang.String title()`

### `public final java.lang.String toString()`

### `public java.lang.String version()`

### `public java.util.List<io.casehub.api.view.WorkerView> workers()`
