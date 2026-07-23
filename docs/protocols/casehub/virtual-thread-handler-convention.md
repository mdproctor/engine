---
id: PP-20260723-c4c1cf
title: "All @ConsumeEvent handlers use @RunOnVirtualThread + void"
type: rule
scope: platform
applies_to: "Any @ConsumeEvent handler in casehub-engine or its modules"
severity: important
refs:
  - docs/guides/virtual-thread-migration.md
violation_hint: "Handler returns Uni<Void> or uses blocking=true instead of @RunOnVirtualThread"
created: 2026-07-23
---

Post virtual-thread migration (engine#770): every `@ConsumeEvent` handler must return `void` and use `@RunOnVirtualThread`. No `Uni<Void>` return types. No `blocking = true`. No `runSubscriptionOn()` wrappers. The handler body is sequential blocking code — virtual threads handle the concurrency. Reactive SPIs (`Reactive*`) no longer exist; inject the blocking SPI directly. Persistence calls use `EntityManager` + `@Transactional`, not Panache Reactive.
