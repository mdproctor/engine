# io.casehub.api.engine.CaseCompensationService

**Package:** `io.casehub.api.engine`

**Kind:** `interface`

Saga coordinator for case-level compensation. When compensation is triggered on a completed case,
the engine executes compensating bindings in reverse topological order.

## Methods

### `public abstract void compensate(java.util.UUID caseId, java.lang.String triggeredBy, java.lang.String reason)`

Trigger compensation for a case.

<p>Valid entry points:

<ul>
  <li>`COMPLETED \u2192 COMPENSATING` — initial compensation
  <li>`COMPENSATION_FAULTED \u2192 COMPENSATING` — retry from faulted step
</ul>

#### Parameters

- `caseId` (`java.util.UUID`) — the case to compensate
- `triggeredBy` (`java.lang.String`) — actor who triggered compensation (operator ID or "system")
- `reason` (`java.lang.String`) — human-readable reason for compensation

#### Throws

- `IllegalStateException` — if case is not COMPLETED or COMPENSATION_FAULTED
