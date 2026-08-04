# io.casehub.api.spi.routing.RoutingDecisionRecord

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Compliance audit record for a trust-weighted routing decision.

## Fields

### `capabilityTag` (`java.lang.String`)

### `evidenceEntryId` (`java.util.UUID`)

### `thresholdApplied` (`double`)

### `trustScoreAtRouting` (`java.lang.Double`)

### `workerId` (`java.lang.String`)

## Record Components

### `capabilityTag` (`java.lang.String`)

the capability being routed

### `evidenceEntryId` (`java.util.UUID`)

UUID reference to the attestation or ledger entry

### `thresholdApplied` (`double`)

the threshold that was applied

### `trustScoreAtRouting` (`java.lang.Double`)

trust score at decision time; null if bootstrap

### `workerId` (`java.lang.String`)

the selected worker

## Constructors

### `public RoutingDecisionRecord(java.lang.String capabilityTag, java.lang.String workerId, java.lang.Double trustScoreAtRouting, double thresholdApplied, java.util.UUID evidenceEntryId)`

#### Parameters

- `capabilityTag` (`java.lang.String`)
- `workerId` (`java.lang.String`)
- `trustScoreAtRouting` (`java.lang.Double`)
- `thresholdApplied` (`double`)
- `evidenceEntryId` (`java.util.UUID`)

## Methods

### `public java.lang.String capabilityTag()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.UUID evidenceEntryId()`

### `public final int hashCode()`

### `public double thresholdApplied()`

### `public final java.lang.String toString()`

### `public java.lang.Double trustScoreAtRouting()`

### `public java.lang.String workerId()`
