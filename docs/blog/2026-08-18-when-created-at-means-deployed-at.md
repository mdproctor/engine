---
title: "When \"Created At\" Means \"Deployed At\""
date: 2026-08-18
entry_type: note
subtype: diary
projects: [casehub-engine]
series: issue-919-add-createdat-caseinstance
tags: [domain-model, jpa, semantic-correctness]
author: mdp
---

`CaseInstanceResponse` had a `createdAt` field from day one. It was labelled "Case creation timestamp" in the OpenAPI schema. The value came from `CaseMetaModel.getCreatedAt()` — which records when the *definition* was deployed, not when the instance was created.

Multiple instances share the same `CaseMetaModel`. A definition deployed on Monday that spawns ten instances throughout the week reports all ten as created on Monday. casehub-soc hit this first — their incident summary view was forced to use `Instant.now()` as a placeholder because the real timestamp didn't exist.

The fix is small: add `Instant createdAt` to `CaseInstance`, set it in `CaseHubReactor.buildInstance()`, persist it via JPA, and point the REST and GraphQL DTOs at the instance instead of the meta model. One field, one Flyway migration, three `from()` factory methods corrected.

The one design choice worth noting: where to assign the timestamp. `CaseMetaModel` sets its `createdAt` inside the JPA repository's `save()` method — a persistence-layer concern. We broke from that pattern and set it in the domain layer instead. The reasoning is straightforward: `CaseInstance` has two persistence implementations (JPA and in-memory), and the in-memory store holds the domain object directly. A persistence-layer assignment would leave the in-memory path without a timestamp until someone remembered to set it. Domain-layer assignment means the value exists on the object the moment it's constructed, regardless of which store eventually persists it.

The Flyway migration backfills existing rows with `DEFAULT NOW()`. Those rows never had a creation timestamp — a migration-time default is imprecise but honest, and it keeps the column NOT NULL so consumers don't need null handling.

With this landed, casehub-soc can drop its `Instant.now()` workaround and use `ci.getCreatedAt()` directly.
