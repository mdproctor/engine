---
layout: post
title: "The Store That Forgot to Persist"
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [sse, persistence, spi, mutiny, broadcast]
---

The orchestration workbench needed an SSE endpoint streaming execution state. The engine already had all the data — `ExecutionStateSnapshot` composing plan model, DAG plan, and DAG result into a single view — but served it via GET at `/plan/state`. Wrong protocol, wrong path.

The fix looked straightforward: a `BroadcastProcessor` observing the same CDI events as the existing `CaseStreamBroadcaster`, composing a full snapshot on each trigger instead of wrapping a thin notification. Wire it to a new resource at `/api/v1/cases/{caseId}/state`. Done.

Then I looked at `ExecutionSnapshotStore` — the SPI backing the snapshot data — and noticed it had only an in-memory implementation. The interface carried `tenancyId` on every read method, clearly designed for multi-tenant persistence. But the sole implementation was a `ConcurrentHashMap` with a 60-minute TTL that vanished on JVM restart. After a restart, the SSE endpoint would have nothing to stream.

The store methods were also missing `tenancyId` on writes — the in-memory impl didn't care, but any JPA implementation would need it for row-level security. And `storeDecomposition()` had zero production callers anywhere in the codebase. A method on the SPI that nobody calls.

We added `tenancyId` to the write methods, threaded it through `SnapshotCapturingDagEventListener` (the sole caller), and built a JPA implementation with three nullable JSONB columns — one entity per case, because the three snapshots share lifecycle and are always queried together.

The broadcaster itself surfaced one worth-knowing gotcha. The initial implementation guarded with `if (snapshot != null)` before pushing to the `BroadcastProcessor` — defensive, looks correct. But when the stores are empty (no execution data for that case), the composition returns null and nothing is pushed. Subscribers waiting on the hot stream hang forever. No error, no log, no timeout. The fix: always compose and push. IDLE is valid data. "No event" and "empty event" have different semantics in a hot stream — the null guard conflates them.

The workbench expects `${endpoint}/${id}/state` with `endpoint="/api/v1/cases"`. The endpoint now exists. The persistence gap is closed. The dead SPI method is still there — harmless, and removing it is a separate concern.
