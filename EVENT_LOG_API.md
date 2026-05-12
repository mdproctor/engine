# Event Log Query API

## Overview

REST API endpoint for retrieving events (EventLog) of a specific case with the ability to filter by event types and stream types, sorted by sequence number (seq).

## Endpoint

```
GET /api/cases/{caseId}/events
```

### Path Parameters

- `caseId` (UUID, required) - case identifier

### Query Parameters

- `eventType` (string, optional, repeatable) - filter by event type. Multiple values can be specified.
  - Allowed values: `CASE_STARTED`, `CASE_COMPLETED`, `CASE_FAULTED`, `CASE_CANCELLED`, `CASE_STATUS_CHANGED`, `TASK_CREATED`, `TASK_COMPLETED`, `TASK_FAILED`, `TASK_CANCELLED`, `WORKER_SCHEDULED`, `WORKER_EXECUTION_STARTED`, `WORKER_EXECUTION_COMPLETED`, `WORKER_EXECUTION_FAILED`, `WORK_SUBMITTED`, `WORK_COMPLETED`, `SIGNAL_RECEIVED`, `MILESTONE_REACHED`, `MILESTONE_ACTIVATED`, `MILESTONE_COMPLETED`, `MILESTONE_SLA_VIOLATED`, `GOAL_REACHED`, `SUBCASE_STARTED`, `SUBCASE_COMPLETED`

- `streamType` (string, optional, repeatable) - filter by stream type. Multiple values can be specified.
  - Allowed values: `CASE`, `WORKER`, `TIMER`, `SYSTEM`

## Response

Array of EventLogDTO objects sorted by `seq` field in ascending order.

### EventLogDTO Structure

```json
{
  "id": 123,
  "caseId": "550e8400-e29b-41d4-a716-446655440000",
  "seq": 1,
  "eventType": "CASE_STARTED",
  "streamType": "CASE",
  "workerId": "worker-1",
  "timestamp": "2026-05-07T10:00:00Z",
  "payload": {},
  "metadata": {}
}
```

## Examples

### Get all case events

```bash
curl http://localhost:8080/api/cases/550e8400-e29b-41d4-a716-446655440000/events
```

### Get only WORKER stream type events

```bash
curl "http://localhost:8080/api/cases/550e8400-e29b-41d4-a716-446655440000/events?streamType=WORKER"
```

### Get worker execution started and completed events

```bash
curl "http://localhost:8080/api/cases/550e8400-e29b-41d4-a716-446655440000/events?eventType=WORKER_EXECUTION_STARTED&eventType=WORKER_EXECUTION_COMPLETED"
```

### Combined filter: CASE stream events of type CASE_STARTED or CASE_COMPLETED

```bash
curl "http://localhost:8080/api/cases/550e8400-e29b-41d4-a716-446655440000/events?streamType=CASE&eventType=CASE_STARTED&eventType=CASE_COMPLETED"
```

## Implementation Details

### Repository Layer

New method in `EventLogRepository`:

```java
Uni<List<EventLog>> findByCaseWithFilters(
    UUID caseId, 
    Collection<CaseHubEventType> eventTypes, 
    Collection<EventStreamType> streamTypes
);
```

- If `eventTypes` or `streamTypes` are `null` or empty, the corresponding filter is not applied
- Results are always sorted by `seq` in ascending order
- Implemented in `JpaEventLogRepository` and `InMemoryEventLogRepository`

### REST Layer

- `EventLogResource` - JAX-RS resource
- `EventLogDTO` - DTO for EventLog serialization

## Testing

Implementation is tested in:
- `JpaEventLogRepositoryTest` (Hibernate persistence)
- `InMemoryEventLogRepositoryTest` (in-memory persistence)
