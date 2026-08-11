---
title: "Worker Data Coordination — From Camel Analysis to Foundation Types"
date: 2026-08-08
status: draft
tags: [engine, exchange, data-channel, camel, design]
issue: 633
---

The Blackboard has been the only data coordination path for workers since day one. Every worker reads from CaseContext via JQ projection, writes output that gets merged back, and the shared mutable state is how bindings communicate. It works — but it's the only option. Two patterns have been missing: discrete payload handoff with metadata (1→1), and continuous streaming between workers with backpressure.

The issue (#633) came out of the blocks consolidation brainstorming. The original framing referenced Camel's Exchange/Message model and suggested DataChannel for streaming pipes. The question was whether to use Camel directly or build native types.

## Why not Camel

The analysis traced what Camel actually is versus what the engine actually needs. Camel excels at integration patterns *within* a route — its 300+ components connect to external systems, and its routing DSL implements the Enterprise Integration Patterns. But the gap here is inter-worker coordination, not intra-worker integration. Camel's mutable Exchange fights platform immutability conventions. Its routing engine overlaps with casehub's binding model. Its threading model conflicts with the engine's virtual threads + Quartz execution layer.

The decision: take the Exchange concept (proven by Camel) and build it native to casehub's execution model. A Camel adapter bridges at the boundary — casehub's immutable Exchange maps to/from Camel's mutable one. The existing `workers-camel` module already does something similar at the Map boundary; the new adapter operates at the Exchange type level.

## What Exchange became

Exchange is a pure data envelope — body, headers, properties. No ID (it's a value type), no exception state (WorkerOutcome handles that), no separate Message type. This is cleaner than Camel's model where Exchange carries both data and error state.

Headers propagate across binding dispatches. Properties don't — they're scoped to Tier 1 chains (pipeline-local state). The Blackboard relationship is a composable strategy: DualWrite (default — body projects to CaseContext AND Exchange threads forward), ExchangeOnly, Full, or custom JQ projection. Per binding, not system-wide.

DataChannel is a blocking `send()`/`receive()` interface — no Mutiny dependency in the foundation tier. Virtual-thread-safe by design. Channel lifecycle mirrors the existing scope model: declared channels for Tier 2/3 (engine-managed), ad-hoc channels for Tier 1 (worker-managed).

## Implementation progress

The design review ran standard depth across coherence, structure, robustness, and cross-cutting. The review improved several areas — `ExchangeAwareFunction` as a marker interface (single detection point for all Exchange-typed variants), blocking DataChannel API instead of Multi, null body semantics, ChannelDeclaration rejecting BINDING scope, and JPA-persisted exchangeHeaders on CaseInstance.

Tasks 1–4 of 10 are complete. Foundation types are in `casehubio/worker` (Exchange, ExchangeAwareFunction, ExchangeProcessor, andThen composition, DataChannel, ChannelRef, WorkerScope channel methods — 101 tests). Engine SPIs and model additions are in `casehubio/engine` (ExchangeProjectionStrategy, DataChannelFactory, ChannelDeclaration, Binding/CaseDefinition/CaseInstance field additions — 13 tests). The cross-repo slot .m2 isolation issue was instructive — slots have their own Maven local repository, and installing SNAPSHOTs to the host .m2 doesn't reach the slot engine.

Next session picks up Task 5 (InMemoryDataChannel + DataChannelRegistry) through Task 10 (Camel adapter + end-to-end tests).
