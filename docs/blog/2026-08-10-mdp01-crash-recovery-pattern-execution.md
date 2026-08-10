---
layout: post
title: "Crash Recovery for Pattern Execution"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [agentic, checkpointing, durability, pattern-execution]
series: issue-881-agentic-planning
---

The agentic pattern infrastructure can now survive a JVM crash mid-execution. Before this, if the process died while a DEBATE or SEQUENCE pattern was running, the engine retried from scratch — every agent re-invoked, every result recomputed. For short-lived patterns that's fine. For a five-round debate with LLM judges, it's expensive and potentially non-deterministic.

The fix is iteration-level checkpointing. After each complete iteration of the driver loop, the engine writes a `PATTERN_CHECKPOINT` event to the EventLog — the same audit trail that already records every other engine event. The checkpoint captures the driver's full resumable state: completed iteration count, all agent results so far, and the per-agent activation and idle counts that the driver uses for routing decisions.

The interesting design constraint was doing this without modifying blocks. The `OrchestratedDriver` lives in the blocks module, which owns the DSL and composition model. The engine provides durability around it — that's the whole point of the guest/host architecture. Changing the driver to support checkpointing would have violated that boundary.

The solution is `ResumableDriver`, a subclass of `OrchestratedDriver` that overrides `runLoop()`. On recovery, the handler queries the EventLog for the latest checkpoint, reconstructs the driver state — pre-populating `allResults`, restoring `activationCounts` and `consecutiveIdleCounts` from the checkpoint's string-keyed maps — and starts the iteration loop from where it left off. The mapping from string agent IDs back to `AgentRef` objects uses the model's candidate supplier, which reconstructs the same candidates from the `CaseDefinition`. For agents not found in the current candidate set (definition changed between crash and recovery), a placeholder ref prevents deserialization failures.

One deliberate asymmetry: HTN patterns don't get true resume in v1. Standard patterns (DEBATE, VOTING, SUPERVISOR, SEQUENCE) use `ResumableDriver` for genuine iteration-skip recovery. HTN patterns store checkpoints — the data is there for observability and future recovery — but on crash they re-decompose and re-execute from scratch. HTN recovery is harder because re-planning interleaves with execution: the `HtnExecutor` manages its own decompose-run-replan loop, and splicing a checkpoint into that requires blocks-side changes. The checkpoint data makes that future work straightforward when we get to it.

The `CheckpointingListener` itself is worth noting for what it doesn't do. It implements blocks' `ExecutionEventListener` — the same observer SPI that the `ResultCollector` uses for re-planning context. It captures results via `onAgentResult()` and writes checkpoints on `onTermination(Continue)`. But it takes a `BiConsumer<PatternExecutionCheckpoint, String>` callback instead of injecting the store directly. The handler provides `checkpointStore::save` as the callback. This keeps the listener free of CDI dependencies — it's a plain object that the handler creates per-execution, not an application-scoped bean.

This closes the last piece of the agentic planning epic. The pattern infrastructure now has configurable backends (#886), planning constraints (#884), failure-aware re-planning (#882), and crash-resilient checkpointing (#883). What's missing is wiring `ChannelAgent` and `HumanAgent` through the engine invoker — those need Qhorus channel and WorkItem SPIs that `WorkerRuntime` doesn't expose yet.
