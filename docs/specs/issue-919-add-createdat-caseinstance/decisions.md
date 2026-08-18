## D1: Timestamp assignment layer

**Choice:** Domain layer — set `createdAt = Instant.now()` in `CaseHubReactor.buildInstance()`
**Alternatives:**
- Persistence layer (JPA `save()`) — follows `CaseMetaModel` pattern but leaves in-memory impl without a timestamp until explicitly set
- Derive from EventLog — no schema change but adds a query per fetch
**Rationale:** Setting in the domain layer means the timestamp is available immediately on the object, consistent across both persistence implementations (JPA and in-memory), and doesn't require a repository round-trip.
**Trade-offs:** Diverges from `CaseMetaModel.createdAt` pattern (which sets in JPA `save()`). Minor — the in-memory repo stores the object directly so the domain-set value is preserved either way.
**Sources:** CaseHubReactor.java:244, JpaCaseMetaModelRepository.java:55, InMemoryCaseInstanceRepository.java:55
**Exploration:** quick
**Status:** captured

## D2: Column nullability and backfill

**Choice:** NOT NULL with DEFAULT NOW() — Flyway migration backfills existing rows with the migration timestamp
**Alternatives:**
- Nullable column — avoids backfill but breaks the non-null REST/GraphQL contract and forces null handling everywhere
- Nullable + batch update from EventLog — accurate but complex migration for a field that didn't previously exist
**Rationale:** Existing rows never had a creation timestamp. A migration-time default is honest enough and keeps the column NOT NULL, matching the `CaseMetaModel.createdAt` pattern. Consumers get a consistent non-null contract.
**Trade-offs:** Historical rows show migration time, not actual creation time. Acceptable — there is no source of truth for the original creation time of those rows.
**Sources:** V1.5.0__Add_Case_Instance_Actor_Id.sql (existing ALTER TABLE pattern), CaseMetaModelEntity.java:64
**Exploration:** quick
**Status:** captured
