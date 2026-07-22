---
id: PP-20260722-60e519
title: "Verify source repo location before changing foundation tier types"
type: rule
scope: platform
applies_to: "Any change to WorkerFunction, WorkerResult, WorkerOutcome, Worker, or other casehub-worker-api types"
severity: important
refs:
  - docs/specs/2026-07-22-typed-composition-context-isolation-design.md
violation_hint: "Editing engine repo files expecting to find WorkerFunction source — it's in casehubio/worker, not a module within casehub-engine"
created: 2026-07-22
---

Foundation tier types (WorkerFunction, WorkerResult, WorkerOutcome, Worker) live in casehubio/worker — a separate repo, not a module within casehub-engine. CLAUDE.md documents the package (`io.casehub.worker.api`) but not the source repo. Before modifying foundation types: locate the source repo, create/switch branches there, make changes, install the SNAPSHOT, then update consumers. The engine depends on the published artifact — changes to the engine alone cannot modify these types.
