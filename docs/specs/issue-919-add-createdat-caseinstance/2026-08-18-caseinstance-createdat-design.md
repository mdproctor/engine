# Add createdAt Timestamp to CaseInstance

**Issue:** engine#919
**Raised from:** casehub-soc#32
**Date:** 2026-08-18

## Problem

`CaseInstance` has no creation timestamp. `CaseInstanceResponse` and `CaseInstanceType`
use `CaseMetaModel.getCreatedAt()` — the definition deployment time — mislabeled as
"Case creation timestamp." Multiple instances share the same `CaseMetaModel`, making this
semantically wrong for instance creation time.

## Design

Add a `createdAt` field to `CaseInstance` set at instance construction time in the domain
layer. Follow the established `CaseMetaModel.createdAt` pattern for JPA persistence.

### Domain model (casehub-engine-common)

`CaseInstance.java` gains:

```java
private Instant createdAt;
// getter + setter
```

### Instance construction (casehub-engine runtime)

`CaseHubReactor.buildInstance()` sets `instance.setCreatedAt(Instant.now())` alongside
the other field assignments. This is the single authoritative creation site — all
CaseInstance objects originate here.

Setting in the domain layer (not the persistence layer) means:
- Timestamp is available immediately on the object before persistence
- Consistent across both JPA and in-memory implementations
- In-memory stores the object directly, so the value is preserved without additional code

### JPA persistence (casehub-persistence-hibernate)

**Entity:** `CaseInstanceEntity` gains:

```java
@Column(name = "created_at", nullable = false, updatable = false)
public Instant createdAt;
```

Matching the `CaseMetaModelEntity.createdAt` annotation pattern.

**Repository — `JpaCaseInstanceRepository`:**
- `save()`: `entity.createdAt = instance.getCreatedAt().truncatedTo(ChronoUnit.MICROS)`
  (microsecond truncation for PostgreSQL timestamp precision alignment — established pattern)
- `fromEntity()`: `instance.setCreatedAt(entity.createdAt)`
- `update()`: No change — `updatable = false` on the column annotation handles this

**Repository — `JpaCrossTenantCaseInstanceRepository`:**
- `fromEntity()`: `instance.setCreatedAt(entity.createdAt)`

### In-memory persistence (casehub-persistence-memory)

No changes. `InMemoryCaseInstanceRepository` stores the domain object directly — the
domain-set timestamp is preserved.

### Flyway migration

`V1.10.0__Add_Case_Instance_Created_At.sql`:

```sql
ALTER TABLE case_instance ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT NOW();
```

`DEFAULT NOW()` backfills existing rows with the migration timestamp. Historical rows
never had a creation timestamp — a migration-time default is honest enough and keeps
the column NOT NULL. Matches the established ALTER TABLE pattern (V1.5.0, V1.7.0).

### REST response (casehub-engine-rest)

`CaseInstanceResponse.from()` changes from `meta.getCreatedAt()` to
`instance.getCreatedAt()`. The record structure is unchanged — `createdAt` is already
a field. No API break.

### GraphQL response (casehub-engine-graphql)

`CaseInstanceType.from()` changes from `meta.getCreatedAt()` to
`instance.getCreatedAt()`. Same pattern as REST.

## Production files changed

| File | Module | Change |
|------|--------|--------|
| `CaseInstance.java` | common | Add field + getter/setter |
| `CaseHubReactor.java` | runtime | Set `createdAt` in `buildInstance()` |
| `CaseInstanceEntity.java` | persistence-hibernate | Add JPA column |
| `JpaCaseInstanceRepository.java` | persistence-hibernate | `save()` + `fromEntity()` |
| `JpaCrossTenantCaseInstanceRepository.java` | persistence-hibernate | `fromEntity()` |
| `CaseInstanceResponse.java` | rest | Fix `from()` factory |
| `CaseInstanceType.java` | graphql | Fix `from()` factory |
| `V1.10.0__Add_Case_Instance_Created_At.sql` | persistence-hibernate | New migration |

## Test changes

- `CaseInstanceRepositoryContractTest`: Add test verifying `createdAt` round-trips
  through save/findByUuid
- `PersistenceIntegrationTest`: Set `createdAt` on test instances that go through
  JPA save (NOT NULL column requires it)

Test files that construct `CaseInstance` directly (~60 files) do NOT need changes — they
don't assert on `createdAt` and the field defaults to null in the domain object (only the
JPA column is NOT NULL).

## Cross-repo impact

- **casehub-soc#32**: Can resolve with `ci.getCreatedAt()` instead of workarounds.
  No code change required in this PR — downstream benefit only.
- No breaking changes. `CaseInstance` is internal to engine-common. The REST/GraphQL
  response structure is unchanged.

## Not in scope

- Removing `CaseMetaModel.getCreatedAt()` from any API — it remains meaningful as the
  definition deployment timestamp
- Adding `updatedAt` or other audit timestamps — separate concern

## References

- `CaseHubReactor.java:244` — CaseInstance construction site
- `CaseMetaModelEntity.java:64` — established `createdAt` JPA pattern
- `JpaCaseMetaModelRepository.java:55` — microsecond truncation pattern
- `V1.5.0__Add_Case_Instance_Actor_Id.sql` — established ALTER TABLE migration pattern
- casehub-soc#32 — downstream consumer that motivated this issue
