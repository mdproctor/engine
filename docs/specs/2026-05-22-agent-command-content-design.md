# Design: Agent Transformer Decoupling + CommandContent Record

**Issues:** casehubio/engine#316, casehubio/engine#301
**Date:** 2026-05-22
**Status:** Approved

---

## engine#316 — Decouple Agent from JqTransformer

### Problem

`Agent` holds two `JqTransformer` fields (`inputTransformer`, `outputTransformer`). `JQEvaluator`
lives in `casehub-engine-common`, which depends on `casehub-engine-api` — making it impossible to
import `JQEvaluator` directly in `Agent`. CDI callers building case definitions with AI workers
cannot supply evaluator-backed transformers. `Agent` is also coupled to a specific jq implementation
when all it needs is a function.

### Design

**`Agent`** holds `UnaryOperator<JsonNode>` for each transformer — no jq import, no `JqTransformer`:

```java
public record Agent(
    String name,
    String description,
    UnaryOperator<JsonNode> inputTransformer,
    UnaryOperator<JsonNode> outputTransformer,
    Function<Map<String, Object>, Map<String, Object>> function
) { ... }
```

**`AgentBuilder`** keeps `inputSchema(String)` and `outputSchema(String)` for convenience —
these construct `JqTransformer` internally and wrap in a lambda. No change for existing callers.
Adds `inputTransformer(UnaryOperator<JsonNode>)` and `outputTransformer(UnaryOperator<JsonNode>)`
for CDI callers who want to supply a `JQEvaluator`-backed function:

```java
// Existing convenience (unchanged call sites):
public AgentBuilder inputSchema(String jqExpression) {
    final JqTransformer t = new JqTransformer(jqExpression);
    return this.inputTransformer(t::apply);
}

// New: CDI callers can supply JQEvaluator-backed function:
public AgentBuilder inputTransformer(UnaryOperator<JsonNode> fn) {
    this.inputTransformer = fn;
    return this;
}
```

`JqTransformer` stays (needed by `AgentBuilder` as an internal detail). When `casehub-platform`
extracts the canonical evaluator (platform#23), `AgentBuilder` can switch to using it — no change
to `Agent`'s API.

### No-transformer case

If `inputSchema`/`outputSchema` was never set, the transformer should be identity:
`UnaryOperator.identity()`. `AgentBuilder.build()` defaults to identity when null.

### Testing

Unit tests on `Agent.execute()` supplying a lambda transformer — no `JqTransformer` dependency
in the test. Integration test confirming `inputSchema(String)` still compiles and produces the
right output (via `JqTransformer` under the hood).

---

## engine#301 — CommandContent typed record

### Problem

`WorkerScheduleEventHandler.dispatchCommand()` builds the COMMAND content as a raw
`Map<String, Object>` with untyped string keys. Typos in key names are silent. The wire format
(type, capability, correlationId, input, deadline) is undocumented as a type.

### Design

New package-private record in `casehub-engine` (runtime module):

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
record CommandContent(
    String type,
    String capability,
    String correlationId,
    Map<String, Object> input,
    String deadline  // null when no case budget deadline; omitted from JSON by @JsonInclude
) {}
```

`dispatchCommand()` constructs `CommandContent` and passes it to `OBJECT_MAPPER.valueToTree()`
for serialization. The resulting JSON is identical to the current HashMap output — no wire
format change, no impact on claudony or any consumer.

`deadline` is a plain `String` (null when no deadline). `@JsonInclude(NON_NULL)` on the record
ensures it is omitted from the serialized JSON when null, matching the current `ifPresent`
behaviour.

### Location

`engine/src/main/java/io/casehub/engine/internal/worker/CommandContent.java` — package-private,
internal to the engine. Claudony reads JSON; no shared type needed.

### Testing

Unit test on `CommandContent` serialization — verify deadline is omitted when null, present when
set. Existing `SpiWiringIntegrationTest.commandDispatchedToChannelWhenWorkerScheduled` already
asserts the wire format contains `"type":"COMMAND"` and the capability name (covers the
happy path, though the test cannot currently run due to engine#321).

---

## Out of Scope

- Deleting `JqTransformer` — deferred to platform#23 (once the platform expression module
  lands, `AgentBuilder`'s internal use can be replaced)
- Moving `CommandContent` to a shared api module — claudony uses JSON; no shared type needed
