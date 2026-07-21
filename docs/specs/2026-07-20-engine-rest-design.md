# Engine REST Module — Design Spec

**Issue:** casehubio/engine#762
**Date:** 2026-07-20
**Status:** Draft

## Summary

Move the REST surface currently in `casehubio/scaffold` into a new
`casehub-engine-rest` module within the engine repo. The module is an opt-in
JAX-RS library — consumers who only need the Java SPI skip it.

This is the first engine module built on the virtual thread direction
(see parent ADR). It uses `@RunOnVirtualThread` with blocking SPIs,
no Uni/Mutiny, no Hibernate Reactive.

## Migration Approach

Move-and-refactor, not greenfield rewrite. Scaffold code is the starting point:

1. Create module structure + pom in engine
2. Move files from `io.casehub.flow.rest` / `io.casehub.flow.service` to
   `io.casehub.engine.rest`
3. Refactor in place:
   - Strip Uni chains → imperative code with `@RunOnVirtualThread`
   - Strip inline ACL checks — the REST library does not enforce ACL;
     deployment-specific authorization stays in the consumer (scaffold
     retains its `AccessControlProvider` checks until `@RolesAllowed`
     adoption, tracked separately). Diverges from issue #762 "move as-is"
     — superseded by the library module boundary; auth-retrofit-readiness
     protocol (PP-20260513-auth-retrofit) places auth at the adapter, not
     in shared libraries
   - Replace Panache / service calls with blocking SPI calls
   - Replace scaffold's three Panache-coupled services
     (`CaseDefinitionService`, `CaseInstanceService`, `EventLogService`)
     with a thin `CaseService` that encapsulates multi-step flows
     (startCase) without Panache coupling. Resources remain thin
     dispatchers per auth-retrofit-readiness protocol Rule 2
4. Add SPI query objects + pagination methods
5. Update scaffold to depend on `casehub-engine-rest`, delete its copies

**What moves as-is:** DTOs (re-packaged), exception mappers, OpenAPI
annotations, endpoint paths, request/response contracts.

**What changes during refactor:** resource method bodies (Uni → imperative,
ACL removed, SPI injection), service layer (three Panache-coupled services
→ one thin `CaseService`).

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Threading model | `@RunOnVirtualThread` + blocking SPIs | Platform direction: virtual threads over reactive for request/response code |
| Persistence access | Through SPIs only, no Panache | SPIs abstract the database — works with PostgreSQL, MongoDB, H2, in-memory |
| ACL | Not in REST library | Library module; consumer owns auth. Scaffold retains ACL as deployment config until `@RolesAllowed` adoption. Diverges from issue #762 "move as-is" — library boundary makes this the wrong layer for ACL |
| Service layer | Thin `CaseService` | Scaffold's three Panache-coupled services replaced by one thin service for multi-step flows. Resources are thin dispatchers per auth-retrofit-readiness protocol Rule 2 |
| Pagination | Query objects on blocking SPIs | Follows work's `AuditQuery` pattern — persistence implementations handle database-level pagination |
| Error responses | RFC 7807 ProblemDetail | Follows scaffold convention, structured error format |
| OpenAPI | Keep annotations | Documentation, not logic |
| Reactive types | No Uni return types in REST layer | Resource methods and `CaseService` are imperative. `CompletionStage` from engine SPIs is joined on virtual threads. Mutiny is a transitive compile dependency via `casehub-engine-common` |

## Module Structure

```
rest/
  pom.xml
  src/main/java/io/casehub/engine/rest/
    CaseDefinitionResource.java
    CaseInstanceResource.java
    CaseControlResource.java
    SignalResource.java
    EventLogResource.java
    service/
      CaseService.java
    dto/
      StartCaseRequest.java
      CaseInstanceResponse.java
      CaseControlRequest.java
      CaseControlResponse.java
      SendSignalRequest.java
      SignalResponse.java
      EventLogEntryResponse.java
      PagedResponse.java
      ProblemDetail.java
    exception/
      EntityNotFoundException.java
      ConstraintViolationExceptionMapper.java
      AccessDeniedExceptionMapper.java
      EntityNotFoundExceptionMapper.java
      IllegalStateExceptionMapper.java
      CatchAllExceptionMapper.java
  src/test/java/io/casehub/engine/rest/
    ...
  src/test/resources/
    application.properties
```

**Artifact:** `casehub-engine-rest`
**Parent:** `casehub-engine-parent`
**Package:** `io.casehub.engine.rest`

**Build:** POM includes `jandex-maven-plugin` per `library-jars-require-jandex`
protocol (PP-20260601-37179a) — resource classes and `@Provider` exception
mappers require CDI discovery when consumed as a library JAR.

## Dependencies

```xml
<!-- Compile -->
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-engine-api</artifactId>
</dependency>
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-engine-common</artifactId>
</dependency>

<!-- Provided — consumer supplies the runtime -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-rest-jackson</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>jakarta.enterprise</groupId>
  <artifactId>jakarta.enterprise.cdi-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>jakarta.ws.rs</groupId>
  <artifactId>jakarta.ws.rs-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>org.eclipse.microprofile.openapi</groupId>
  <artifactId>microprofile-openapi-api</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-hibernate-validator</artifactId>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-platform-api</artifactId>
  <scope>provided</scope>
</dependency>

<!-- Test -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-junit5</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.rest-assured</groupId>
  <artifactId>rest-assured</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-persistence-memory</artifactId>
  <scope>test</scope>
</dependency>
```

## SPI Changes

### New query objects (in `casehub-engine-common`)

Following the query pattern established by `AuditQuery` in `casehub-work`
runtime — builder-style query objects with `page`, `size`, and
domain-specific filters. Persistence implementations handle
database-level pagination. Note: `casehub-work-rest` and
`casehub-ledger-rest` referenced in issue #762 are planned future modules;
`casehub-engine-rest` is the first REST library module in this pattern.

**`CaseDefinitionQuery`**

```java
public final class CaseDefinitionQuery {
    private final String namespace;    // nullable filter
    private final String name;         // nullable filter
    private final int page;            // zero-based
    private final int size;            // capped at 100

    // Builder pattern, static all() factory
}
```

**`CaseInstanceQuery`**

```java
public final class CaseInstanceQuery {
    private final String namespace;    // nullable filter
    private final String name;         // nullable filter
    private final CaseStatus status;   // nullable filter
    private final int page;
    private final int size;

    // Builder pattern, static all() factory
}
```

**`EventLogQuery`**

```java
public final class EventLogQuery {
    private final UUID caseId;                          // required
    private final Collection<CaseHubEventType> eventTypes;   // nullable filter
    private final Collection<EventStreamType> streamTypes;   // nullable filter
    private final int page;
    private final int size;

    // Builder pattern
}
```

### New methods on blocking SPIs

```java
// CaseMetaModelRepository
List<CaseMetaModel> query(CaseDefinitionQuery query, String tenancyId);
long count(CaseDefinitionQuery query, String tenancyId);

// CaseInstanceRepository
List<CaseInstance> query(CaseInstanceQuery query, String tenancyId);
long count(CaseInstanceQuery query, String tenancyId);

// EventLogRepository
List<EventLog> query(EventLogQuery query, String tenancyId);
long count(EventLogQuery query, String tenancyId);
```

Added as `default` methods per SPI evolution protocol (return empty list / 0).
Implemented in `persistence-memory` (stream + skip + limit) and
`persistence-hibernate` (JPQL with LIMIT/OFFSET).

**Contract tests:** Each new `query()` and `count()` method must be added
to the abstract contract tests per `spi-evolution-default-methods` protocol
(PP-20260601-81b9e5). Both `persistence-memory` and `persistence-hibernate`
implementations must pass.

**No reactive counterparts.** Aligns with virtual thread migration direction.

## REST Endpoints

All resource methods annotated with `@RunOnVirtualThread`.
All inject blocking SPIs and `CurrentPrincipal`.

### Case Definitions

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/v1/case-definitions` | 200 | List definitions (paginated) |
| GET | `/api/v1/case-definitions/{namespace}/{name}` | 200 | All versions by namespace+name |
| GET | `/api/v1/case-definitions/{namespace}/{name}/{version}` | 200 | Specific definition |

Resources inject `CaseMetaModelRepository` and `CaseDefinitionRegistry`.
Registry provides definition lookup from meta model. Repository provides
paginated queries.

### Case Instances

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/v1/cases` | 200 | List case instances (paginated) |
| POST | `/api/v1/cases` | 201 | Start a new case (intentional: 201 Created, differs from scaffold's 200) |
| GET | `/api/v1/cases/{caseId}` | 200 | Get case instance |
| GET | `/api/v1/cases/{caseId}/context` | 200 | Get full case context |
| GET | `/api/v1/cases/{caseId}/context/{path}` | 200 | Get context at path |
| POST | `/api/v1/cases/{caseId}/suspend` | 200 | Suspend case (synchronous — 200 not 202) |
| POST | `/api/v1/cases/{caseId}/resume` | 200 | Resume case (synchronous — 200 not 202) |
| POST | `/api/v1/cases/{caseId}/cancel` | 200 | Cancel case (synchronous — 200 not 202) |

`GET /cases` uses `CaseInstanceQuery` with optional `status`, `namespace`,
`name` filters and pagination.

`POST /cases` injects `CaseDefinitionRegistry` to find the definition,
then calls `CaseHubRuntime.startCase()`. The registry's `findByIdentity()`
validates the definition exists before starting.

Control operations (suspend/resume/cancel) call corresponding methods on
`CaseHubRuntime`.

Context queries use `CaseHubRuntime.query()`.

### Signals

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| POST | `/api/v1/cases/{caseId}/signals` | 200 | Send signal to case |

Calls `CaseHubRuntime.signal()`. For typed signals, resolves `SignalType`
from `CaseDefinition.signals` and calls the typed overload.

### Event Log

| Method | Path | Status | Description |
|--------|------|--------|-------------|
| GET | `/api/v1/cases/{caseId}/events` | 200 | List events (paginated, filtered) |

Injects `EventLogRepository`. Uses `EventLogQuery` with optional
`eventType` and `streamType` filters.

## Resource Example

Resources are thin dispatchers — validate, delegate, map response.
Multi-step business logic lives in `CaseService`.

**CaseService** (encapsulates multi-step flows and boundary exception
translation):

```java
@ApplicationScoped
public class CaseService {

    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject CaseHubRuntime runtime;
    @Inject CaseInstanceRepository instanceRepository;

    public CaseInstance startCase(String namespace, String name,
            String version, Map<String, Object> context, String tenancyId) {
        var metaModel = definitionRegistry
            .findByIdentity(namespace, name, version)
            .orElseThrow(() -> new EntityNotFoundException(
                String.format("No definition for %s/%s/%s",
                    namespace, name, version)));

        var definition = definitionRegistry.getCaseDefinition(metaModel);
        if (definition == null) {
            throw new EntityNotFoundException(String.format(
                "Definition metadata exists but body not found for %s/%s/%s",
                namespace, name, version));
        }

        UUID caseId = runtime.startCase(definition, context)
            .toCompletableFuture().join();

        CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
        if (instance == null) {
            throw new RuntimeException(
                "Case created (id=" + caseId
                    + ") but not found in repository");
        }
        return instance;
    }

    public CaseInstance requireCase(UUID caseId, String tenancyId) {
        CaseInstance instance = instanceRepository
            .findByUuid(caseId, tenancyId);
        if (instance == null) {
            throw new EntityNotFoundException("Case not found: " + caseId);
        }
        return instance;
    }
}
```

**CaseInstanceResource** (thin dispatcher):

```java
@Path("/api/v1/cases")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Case Instances")
public class CaseInstanceResource {

    @Inject CaseService caseService;
    @Inject CaseInstanceRepository instanceRepository;
    @Inject CurrentPrincipal currentPrincipal;

    @GET
    @RunOnVirtualThread
    @Operation(summary = "List case instances")
    public PagedResponse<CaseInstanceResponse> listCases(
            @QueryParam("page") @DefaultValue("1") @Min(1) int page,
            @QueryParam("size") @DefaultValue("20") @Min(1) @Max(100) int size,
            @QueryParam("status") CaseStatus status,
            @QueryParam("namespace") String namespace,
            @QueryParam("name") String name) {
        var query = CaseInstanceQuery.builder()
            .status(status).namespace(namespace).name(name)
            .page(page - 1).size(size).build();
        String tenancyId = currentPrincipal.tenancyId();
        var items = instanceRepository.query(query, tenancyId)
            .stream().map(CaseInstanceResponse::from).toList();
        long total = instanceRepository.count(query, tenancyId);
        return new PagedResponse<>(items, page, size, total);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(summary = "Start a new case instance")
    public Response startCase(@Valid StartCaseRequest request) {
        var ref = request.definition();
        Map<String, Object> context =
            request.context() != null ? request.context() : Map.of();

        CaseInstance instance = caseService.startCase(
            ref.namespace(), ref.name(), ref.version(),
            context, currentPrincipal.tenancyId());

        return Response.status(Response.Status.CREATED)
            .entity(CaseInstanceResponse.from(instance))
            .build();
    }

    @GET
    @Path("/{caseId}")
    @RunOnVirtualThread
    @Operation(summary = "Get case instance by ID")
    public CaseInstanceResponse getCaseInstance(
            @PathParam("caseId") UUID caseId) {
        CaseInstance instance = caseService.requireCase(
            caseId, currentPrincipal.tenancyId());
        return CaseInstanceResponse.from(instance);
    }
}
```

**Control and context resource boundary pattern:** Control operations
(`suspend`, `resume`, `cancel`) and context queries (`getContext`,
`getContextPath`) catch `IllegalArgumentException` from `CaseHubRuntime`
and re-throw as `EntityNotFoundException`:

```java
// CaseControlResource — thin dispatch with exception boundary
@POST @Path("/{caseId}/suspend") @RunOnVirtualThread
public CaseControlResponse suspend(@PathParam("caseId") UUID caseId) {
    try {
        runtime.suspendCase(caseId);
    } catch (IllegalArgumentException e) {
        throw new EntityNotFoundException(e.getMessage());
    }
    return new CaseControlResponse(caseId, "suspend", "completed");
}

// CaseInstanceResource — context query with pre-verification
@GET @Path("/{caseId}/context") @RunOnVirtualThread
public Response getContext(@PathParam("caseId") UUID caseId) {
    caseService.requireCase(caseId, currentPrincipal.tenancyId());
    Object context = runtime.query(caseId, ".")
        .toCompletableFuture().join();
    return Response.ok(context).build();
}
```

## Threading Safety

`@RunOnVirtualThread` runs each request on a virtual thread. Blocking
calls (`.toCompletableFuture().join()` on `CompletionStage` from
`CaseHubRuntime`) park the virtual thread without pinning the carrier.

The engine's `CaseHubReactor` builds Mutiny `Uni` chains (reactive
repository calls, Vert.x event bus via `eventBus.request()`) and converts
to `CompletionStage` via `subscribeAsCompletionStage()`. These chains
execute on the Vert.x event loop and Mutiny's infrastructure threads —
not on the caller's virtual thread. The `.join()` call parks the virtual
thread until the `CompletionStage` resolves; the carrier thread is
released back to the `ForkJoinPool` and is available for other virtual
threads.

No deadlock risk: the event loop and the virtual thread carrier pool are
separate thread pools with no circular dependency. Quarkus's
`@RunOnVirtualThread` is specifically designed for this pattern —
imperative code on virtual threads calling async backends. This applies
to all `CompletionStage` → `.join()` conversions: `startCase()`,
`signal()`, `query()`.

## Pagination Convention

REST API uses 1-based page numbers (consistent with scaffold convention).
Query objects use 0-based page index internally. The resource layer
converts: `query.page(clientPage - 1)`. `PagedResponse.page` returns the
1-based page number as received from the client.

**Validation:** All paginated endpoints use Bean Validation on query
parameters: `@Min(1)` on `page`, `@Min(1) @Max(100)` on `size`. Invalid
values produce `ConstraintViolationException` → 400 via the existing
mapper. Query object builders additionally clamp `size` to their
documented maximum as a defense-in-depth measure. This applies to all
three paginated endpoints: `GET /cases`, `GET /case-definitions`, and
`GET /cases/{caseId}/events`.

## DTOs

Records with Bean Validation and OpenAPI annotations. Moved from
`io.casehub.flow.rest.dto` to `io.casehub.engine.rest.dto`.

**`StartCaseRequest`** — nested `CaseDefinitionRef` record, `@NotNull`/`@NotBlank`
on required fields, optional `context` map.

**`CaseInstanceResponse`** — static `from(CaseInstance)` factory method.
Fields: `caseId`, `status`, `namespace`, `name`, `version`, `createdAt`.
`updatedAt` is dropped — neither `CaseInstance` nor `CaseMetaModel` tracks
update timestamps (scaffold used `meta.getCreatedAt()` for both, which is
misleading). If update tracking is needed, it is a model-level concern.

**`PagedResponse<T>`** — generic paginated wrapper.
Fields: `items`, `page`, `size`, `totalElements`, `totalPages`.

**`ProblemDetail`** — RFC 7807.
Fields: `title`, `status`, `detail`.

Other DTOs: `CaseControlRequest`, `CaseControlResponse`, `SendSignalRequest`,
`SignalResponse`, `EventLogEntryResponse`.

## Exception Mappers

All mappers are `@Provider`, produce `ProblemDetail` (RFC 7807).

| Exception | HTTP Status | Rationale |
|-----------|-------------|-----------|
| `ConstraintViolationException` | 400 | Bean Validation failures on request DTOs |
| `AccessDeniedException` | 403 | ACL violations propagated from consumer layer |
| `EntityNotFoundException` | 404 | Module-specific exception for entity not found |
| `IllegalStateException` | 409 | Invalid state transition (suspend non-running case, resume non-suspended, etc.) |
| `RuntimeException` (catch-all) | 500 | Prevents stack trace leaks; logs full exception server-side |

`EntityNotFoundException` is a module-specific exception
(`io.casehub.engine.rest.exception.EntityNotFoundException`). Named to
avoid collision with `jakarta.ws.rs.NotFoundException` (on the classpath
via `jakarta.ws.rs-api`) which extends `WebApplicationException` and is
handled by Quarkus's built-in mapper with non-ProblemDetail responses.
It replaces the previous global `IllegalArgumentException` → 404 mapper,
which had an over-breadth problem: IAE is thrown by engine SPIs for
"not found" but also by builders, validators, and any code with invalid
arguments.

**Boundary translation:** Engine SPIs throw `IllegalArgumentException` for
missing entities (`CaseHubRuntime.cancelCase()`, `.suspendCase()`,
`.resumeCase()`). Resource methods catch IAE from these calls and re-throw
as `EntityNotFoundException`. `CaseHubReactor.query()` throws
`RuntimeException` (not IAE) for not-found cases — context query resource
methods pre-verify case existence via `CaseService.requireCase()` instead
of matching exception messages. The `query()` inconsistency should be
fixed in the engine (tracked as out-of-scope engine issue).

## Testing

`@QuarkusTest` with `casehub-persistence-memory` for in-memory SPI
implementations. Test `application.properties` activates memory alternatives
via `quarkus.arc.selected-alternatives`.

Tests use REST Assured to verify HTTP contracts: status codes, response
bodies, pagination, error responses.

## Scaffold Impact

After engine-rest ships:
1. Scaffold adds `casehub-engine-rest` as a dependency
2. Scaffold removes its inline REST classes (`io.casehub.flow.rest.*`,
   `io.casehub.flow.service.*`)
3. Scaffold registers a JAX-RS `ContainerRequestFilter` that calls
   `AccessControlProvider.canAccess()` before resource method execution —
   the standard mechanism for deployment-level auth on library resources,
   requiring no modification of engine-rest classes. The filter stays in
   place until `@RolesAllowed` adoption replaces it

## Out of Scope

- ACL enforcement via `@RolesAllowed` — file as engine issue, blocked on
  `casehub-platform-oidc` adoption per auth-retrofit-readiness protocol
- `CaseInstance` / `CaseMetaModel` update timestamps (`updatedAt`) — file
  as engine issue; model-level concern, not REST-only
- `CaseHub` interface redesign (separating definition from execution) —
  the spec's approach bypasses the scaffold's reflection hack by going
  directly to `CaseDefinitionRegistry` + `CaseHubRuntime`; the underlying
  interface coupling is a separate design concern
- `CaseHubReactor.query()` exception type inconsistency — throws
  `RuntimeException` for not-found while `requireInstance()` throws
  `IllegalArgumentException`. File as engine issue to align on consistent
  exception types
- Virtual thread migration of existing engine internals (parent ADR, separate epic)
- Reactive SPI counterparts for new query methods (not needed — virtual thread direction)
- WebSocket or SSE endpoints (future, if needed)
