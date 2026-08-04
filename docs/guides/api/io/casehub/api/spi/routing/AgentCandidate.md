# io.casehub.api.spi.routing.AgentCandidate

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

A pre-filtered, pre-probed agent worker candidate passed to `AgentRoutingStrategy.select`.

## Fields

### `agentDescriptor` (`AgentDescriptor`)

### `capabilities` (`java.util.Set<java.lang.String>`)

### `health` (`io.casehub.api.spi.routing.AgentHealth`)

### `matchDegree` (`MatchDegree`)

### `runningJobs` (`int`)

### `workerId` (`java.lang.String`)

## Record Components

### `agentDescriptor` (`AgentDescriptor`)

the agent's registered descriptor from casehub-eidos; null if no
    descriptor is registered for this worker

### `capabilities` (`java.util.Set<java.lang.String>`)

all capabilities declared by this worker

### `health` (`io.casehub.api.spi.routing.AgentHealth`)

pre-probed health status; UNAVAILABLE workers are never included

### `matchDegree` (`MatchDegree`)

how this worker matched the requested capability; null when match metadata is
    unavailable (bootstrap workers without eidos descriptors)

### `runningJobs` (`int`)

count of currently active Quartz execution jobs for this worker

### `workerId` (`java.lang.String`)

the worker name from the case definition YAML

## Constructors

### `public AgentCandidate(java.lang.String workerId, java.util.Set<java.lang.String> capabilities, int runningJobs, io.casehub.api.spi.routing.AgentHealth health, AgentDescriptor agentDescriptor, MatchDegree matchDegree)`

#### Parameters

- `workerId` (`java.lang.String`)
- `capabilities` (`java.util.Set<java.lang.String>`)
- `runningJobs` (`int`)
- `health` (`io.casehub.api.spi.routing.AgentHealth`)
- `agentDescriptor` (`AgentDescriptor`)
- `matchDegree` (`MatchDegree`)

## Methods

### `public AgentDescriptor agentDescriptor()`

### `public java.util.Set<java.lang.String> capabilities()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.api.spi.routing.AgentHealth health()`

### `public MatchDegree matchDegree()`

### `public int runningJobs()`

### `public final java.lang.String toString()`

### `public java.lang.String workerId()`
