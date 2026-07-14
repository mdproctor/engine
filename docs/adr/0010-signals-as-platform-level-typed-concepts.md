# ADR-0010: Signals as platform-level typed concepts

**Status:** Accepted
**Date:** 2026-07-14
**Issues:** engine#691, engine#690

## Context

CaseHub's `signal(caseId, path, value)` API treats signals as arbitrary path writes into the working layer. Any path, any value, no schema, no validation. This worked for initial development but caused three problems as the platform grew:

1. No compile-time safety — callers send untyped data, receivers evaluate untyped JQ.
2. No declaration — cases don't declare what signals they accept. A typo in the signal path silently writes to the wrong key.
3. Inconsistency — the worker boundary has typed context via ContextBridge, but the signal boundary is untyped. Two boundaries in the same system with different typing models.

## Decision

Signals are platform-level typed concepts, not ad-hoc path writes.

`SignalType<T>` is a record with `name` and `payloadType`, declared on `CaseDefinition` alongside capabilities. The runtime validates signal name and payload type at the API layer before event publishing. The untyped `signal(caseId, path, value)` remains as a separate API for dynamic/integration use — it is not deprecated.

Typed signal payloads write to `.signals.{signalName}` in the working layer — namespaced to prevent collision with business context keys.

## Alternatives considered

**Option A — Typed overload without declaration.** `<T> signal(caseId, SignalType<T>, T)` with no `CaseDefinition.signals` list. Provides sender-side type safety but no receiver-side validation. Rejected: the type is known at the sender but invisible at the case — there's no way to validate name or type mismatches, and no documentation of what signals a case expects.

**Option B — Path-based typing with schema validation.** Keep path-based signals but add JSON Schema validation per path on the definition. Rejected: JSON Schema is runtime-only validation with no compile-time safety. Doesn't solve the fundamental problem — signals are still unstructured path writes, just with a validator attached.

**Option C — Replace all untyped signals with typed signals.** Deprecate `signal(caseId, path, value)` entirely. Rejected: the untyped path serves a distinct, permanent purpose — integration patterns where the signal schema is not known at compile time (Qhorus message bridges, dynamic connectors).

## Consequences

- `CaseDefinition` gains `List<SignalType<?>> signals` with duplicate-name validation at build time
- `CaseHubRuntime` gains `signal(UUID, SignalType<T>, T)` overload
- `SignalReceivedEventHandler` gains a typed signal handler writing to the `.signals` namespace
- YAML schema gains `signals:` array with `name` and `contextType`
- Existing untyped signal API unchanged — no migration required
- The same pattern (platform-level typed declaration + runtime validation) can be applied to connectors (#692)
