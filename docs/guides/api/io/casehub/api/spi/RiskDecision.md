# io.casehub.api.spi.RiskDecision

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

The outcome of `ActionRiskClassifier.classify(PlannedAction)`.

<p>`Autonomous` — proceed immediately; case advances as if no PlannedAction was declared.

<p>`GateRequired` — pause the case and route to a human approver via a WorkItem. The engine
fires an `ActionGateScheduleEvent`; `casehub-work-engine-adapter` creates the
WorkItem. If the engine-adapter is absent the case stalls — see `ActionGateDeploymentHealthCheck`.
