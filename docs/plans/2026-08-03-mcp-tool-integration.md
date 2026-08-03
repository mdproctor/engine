# MCP Tool Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #831 — feat: MCP tool integration — invoke MCP server tools as casehub workers
**Issue group:** #830, #831

**Goal:** Invoke MCP server tools as casehub workers with stdio and HTTP transport, tool discovery via `tools/list`, and one function per discovered tool matching the A2A module pattern.

**Architecture:** New `casehub-engine-mcp` module (`mcp/` directory) using the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`). Cross-cutting: `AuthConfig` extracted from A2A to engine-common, `discoverWorkers()` added to `WorkerFunctionProvider` for multi-worker contribution from a single YAML entry. `McpTransport` sealed interface (`Stdio` | `Http`). Each MCP tool becomes a separate `Worker` with its own `McpWorkerFunction(transport, toolName)`.

**Tech Stack:** Java 21, Quarkus CDI, MCP Java SDK 2.0.0 (`McpSyncClient`), JUnit 5, MockWebServer

## Global Constraints

- Directory name: `mcp/` (not `casehub-engine-mcp/`) per maven-submodule-folder-naming protocol
- Artifact ID: `casehub-engine-mcp`
- Compile deps: `casehub-engine-common`, `casehub-engine-api`, `casehub-worker-api`, `io.modelcontextprotocol.sdk:mcp`, `quarkus-arc`, `quarkus-virtual-threads`
- No dependency on `casehub-engine` (runtime) or `casehub-engine-a2a` at compile scope
- Jandex plugin required — module ships CDI beans
- All tests named `*Test.java` (never `*IT.java`)
- Use IntelliJ MCP for all code navigation and structural editing

---

### Task 1: AuthConfig extraction — shared auth type for A2A and MCP

Move `A2AAuthConfig` to `io.casehub.engine.common.internal.auth.AuthConfig` so both modules can use it without depending on each other.

**Files:**
- Create: `common/src/main/java/io/casehub/engine/common/internal/auth/AuthConfig.java`
- Modify: `a2a/src/main/java/io/casehub/engine/a2a/A2AWorkerFunction.java` — change `A2AAuthConfig` → `AuthConfig`
- Modify: `a2a/src/main/java/io/casehub/engine/a2a/A2AWorkerFunctionProvider.java` — change `A2AAuthConfig` → `AuthConfig`
- Modify: `a2a/src/main/java/io/casehub/engine/a2a/A2AClient.java` — change `A2AAuthConfig` → `AuthConfig`
- Modify: `a2a/src/main/java/io/casehub/engine/a2a/A2AClientRegistry.java` — change `A2AAuthConfig` → `AuthConfig`
- Modify: `a2a/src/main/java/io/casehub/engine/a2a/A2AWorkerFunctionHandler.java` — no change (doesn't reference `A2AAuthConfig` directly)
- Delete: `a2a/src/main/java/io/casehub/engine/a2a/A2AAuthConfig.java`
- Modify: all A2A test files referencing `A2AAuthConfig`
- Test: existing A2A tests must pass unchanged (after import updates)

**Interfaces:**
- Produces: `AuthConfig(AuthType type, String tokenConfigKey)` with `AuthConfig.NONE` constant — consumed by Task 3 (MCP types) and all A2A code

- [ ] **Step 1: Create AuthConfig in engine-common**

```java
package io.casehub.engine.common.internal.auth;

import java.util.Objects;

public record AuthConfig(AuthType type, String tokenConfigKey) {

    public enum AuthType { NONE, BEARER, API_KEY }

    public static final AuthConfig NONE = new AuthConfig(AuthType.NONE, null);
}
```

Use `ide_create_file` for `common/src/main/java/io/casehub/engine/common/internal/auth/AuthConfig.java`.

- [ ] **Step 2: Update A2AWorkerFunction to use AuthConfig**

Use `ide_refactor_rename` to rename `A2AAuthConfig` usages. Since this is a cross-module type move (not a simple rename), instead:
1. Use `ide_replace_text_in_file` on each file to replace `A2AAuthConfig` → `AuthConfig` and `io.casehub.engine.a2a.A2AAuthConfig` → `io.casehub.engine.common.internal.auth.AuthConfig`
2. Apply to: `A2AWorkerFunction.java`, `A2AWorkerFunctionProvider.java`, `A2AClient.java`, `A2AClientRegistry.java`
3. Apply to test files: `A2AClientTest.java`, `A2AClientRegistryTest.java`, `A2AWorkerFunctionProviderTest.java`, `A2AWorkerFunctionHandlerTest.java`, `A2AWorkerIntegrationTest.java`

- [ ] **Step 3: Delete A2AAuthConfig.java**

Use `ide_refactor_safe_delete` on `a2a/src/main/java/io/casehub/engine/a2a/A2AAuthConfig.java`.

- [ ] **Step 4: Install common module and run A2A tests**

Run: `/opt/homebrew/bin/mvn install -Dmaven.test.skip=true -q -pl common -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Then: `/opt/homebrew/bin/mvn test -pl a2a -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: All 45 A2A tests pass.

- [ ] **Step 5: Commit**

```
feat(#831): extract AuthConfig to engine-common

Move A2AAuthConfig → AuthConfig in common/internal/auth/ so both
casehub-engine-a2a and casehub-engine-mcp can share it.

Refs #831
```

---

### Task 2: discoverWorkers() — multi-worker contribution from single YAML entry

Add `DiscoveredWorker` record and `discoverWorkers()` default method to `WorkerFunctionProvider`. Update `WorkerFunctionProviderRegistry` and its implementation. Update `CaseDefinitionYamlMapper` to handle discovered workers.

**Files:**
- Create: `api/src/main/java/io/casehub/api/spi/DiscoveredWorker.java`
- Modify: `api/src/main/java/io/casehub/api/spi/WorkerFunctionProvider.java` — add `discoverWorkers()` default
- Modify: `api/src/main/java/io/casehub/api/spi/WorkerFunctionProviderRegistry.java` — add `discoverWorkers()` default
- Modify: `runtime/src/main/java/io/casehub/engine/internal/worker/DefaultWorkerFunctionProviderRegistry.java` — implement `discoverWorkers()`
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` — discovery branch in worker processing
- Test: `api/src/test/java/io/casehub/api/spi/DiscoveredWorkerTest.java`

**Interfaces:**
- Produces: `DiscoveredWorker(String workerName, Capability capability, WorkerFunction<?, ?> function)` — consumed by Task 5 (MCP provider)
- Produces: `WorkerFunctionProvider.discoverWorkers(JsonNode)` — consumed by Task 5

- [ ] **Step 1: Create DiscoveredWorker record**

```java
package io.casehub.api.spi;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.WorkerFunction;
import java.util.Objects;

public record DiscoveredWorker(
    String workerName,
    Capability capability,
    WorkerFunction<?, ?> function
) {
    public DiscoveredWorker {
        Objects.requireNonNull(workerName);
        Objects.requireNonNull(capability);
        Objects.requireNonNull(function);
    }
}
```

Use `ide_create_file`.

- [ ] **Step 2: Add discoverWorkers() to WorkerFunctionProvider**

Use `ide_insert_member` on `WorkerFunctionProvider`:

```java
default java.util.List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
    return java.util.List.of();
}
```

Position: after `create` method.

- [ ] **Step 3: Add discoverWorkers() to WorkerFunctionProviderRegistry**

Use `ide_insert_member` on `WorkerFunctionProviderRegistry`:

```java
default java.util.List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
    return java.util.List.of();
}
```

- [ ] **Step 4: Implement discoverWorkers() in DefaultWorkerFunctionProviderRegistry**

Use `ide_insert_member` on `DefaultWorkerFunctionProviderRegistry`:

```java
@Override
public List<DiscoveredWorker> discoverWorkers(final JsonNode rawWorkerNode) {
    for (final WorkerFunctionProvider provider : providers) {
        if (provider.handles(rawWorkerNode)) {
            final List<DiscoveredWorker> discovered = provider.discoverWorkers(rawWorkerNode);
            if (!discovered.isEmpty()) {
                return discovered;
            }
        }
    }
    return List.of();
}
```

- [ ] **Step 5: Update CaseDefinitionYamlMapper worker processing**

In the mapper's worker loop (around line 360), add a discovery branch before `createFunction`:

```java
// Try discovery first (for multi-worker providers like MCP)
final JsonNode rawWorkerNode = rawWorkers.get(workerIndex);
List<DiscoveredWorker> discovered = providerRegistry.discoverWorkers(rawWorkerNode);
if (!discovered.isEmpty()) {
    for (DiscoveredWorker dw : discovered) {
        if (!capabilityMap.containsKey(dw.capability().name())) {
            capabilityMap.put(dw.capability().name(), dw.capability());
            def.addCapability(dw.capability());
        }
        Worker discoveredWorker = Worker.builder()
            .name(dw.workerName())
            .capabilityName(dw.capability().name())
            .function(dw.function())
            .build();
        builtWorkers.put(dw.workerName(), discoveredWorker);
    }
    workerIndex++;
    continue;
}

// Existing path: try providers then fallback
WorkerFunction<?, ?> function = providerRegistry.createFunction(rawWorkerNode);
```

Use `ide_replace_member` on the worker processing section. The exact edit target is the code block starting at `final JsonNode rawWorkerNode = rawWorkers.get(workerIndex);` (line ~362).

- [ ] **Step 6: Install api, run existing tests**

Run: `/opt/homebrew/bin/mvn install -Dmaven.test.skip=true -q -pl api -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Then: `/opt/homebrew/bin/mvn test -pl a2a -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: All existing tests pass (default method returns empty — zero behavioral change).

- [ ] **Step 7: Commit**

```
feat(#831): DiscoveredWorker and discoverWorkers() on WorkerFunctionProvider

Default method returns empty — zero behavioral change for existing
providers. CaseDefinitionYamlMapper checks discovery before single-
function path. Enables MCP provider to contribute multiple workers
from one YAML declaration.

Refs #831
```

---

### Task 3: Module scaffold + data types

Create the `mcp/` directory, `pom.xml`, and the data types (`McpTransport`, `McpWorkerFunction`).

**Files:**
- Create: `mcp/pom.xml`
- Create: `mcp/src/main/java/io/casehub/engine/mcp/McpTransport.java`
- Create: `mcp/src/main/java/io/casehub/engine/mcp/McpWorkerFunction.java`
- Create: `mcp/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add `<module>mcp</module>`)
- Test: `mcp/src/test/java/io/casehub/engine/mcp/McpWorkerFunctionTest.java`

**Interfaces:**
- Consumes: `AuthConfig` (from Task 1), `WorkerFunction` (worker-api), `Capability` (worker-api)
- Produces: `McpTransport` (sealed: `Stdio`, `Http`), `McpWorkerFunction(McpTransport, String toolName)` — consumed by Tasks 4-6

- [ ] **Step 1: Add module to parent pom.xml**

Add `<module>mcp</module>` after `<module>a2a</module>` in the `<modules>` section using `ide_replace_text_in_file`.

- [ ] **Step 2: Create mcp/pom.xml**

Use Write tool (new file). Include:
- Dependencies: `casehub-engine-common`, `casehub-engine-api`, `casehub-worker-api`, `io.modelcontextprotocol.sdk:mcp`, `quarkus-arc`, `quarkus-virtual-threads`
- Test dependencies: `junit-jupiter`, `assertj-core`, `mockwebserver`, `casehub-engine` (test), `casehub-engine-scheduler-quartz` (test), `casehub-engine-persistence-memory` (test), `casehub-ledger-testing` (test), `casehub-engine-ledger` (test), `quarkus-jdbc-h2` (test), `quarkus-junit` (test), `awaitility` (test), `quarkus-vertx` (test)
- Plugins: quarkus-maven-plugin, maven-compiler-plugin (parameters=true), maven-surefire-plugin, jandex-maven-plugin
- Copy pattern from `a2a/pom.xml`, add `io.modelcontextprotocol.sdk:mcp` compile dependency

- [ ] **Step 3: Create test application.properties**

Copy from `a2a/src/test/resources/application.properties` — same in-memory repos, H2, excluded beans pattern.

- [ ] **Step 4: Create McpTransport sealed interface**

```java
package io.casehub.engine.mcp;

import io.casehub.engine.common.internal.auth.AuthConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface McpTransport {

    record Stdio(List<String> command, Map<String, String> env) implements McpTransport {
        public Stdio {
            Objects.requireNonNull(command);
            if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            env = env != null ? Map.copyOf(env) : Map.of();
        }
    }

    record Http(String url, AuthConfig auth) implements McpTransport {
        public Http {
            Objects.requireNonNull(url);
            auth = auth != null ? auth : AuthConfig.NONE;
        }
    }
}
```

Use `ide_create_file`.

- [ ] **Step 5: Create McpWorkerFunction record**

```java
package io.casehub.engine.mcp;

import io.casehub.worker.api.WorkerFunction;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unchecked")
public record McpWorkerFunction(
    McpTransport transport,
    String toolName
) implements WorkerFunction<Map<String, Object>, Map<String, Object>> {

    public McpWorkerFunction {
        Objects.requireNonNull(transport);
        Objects.requireNonNull(toolName);
    }

    @Override public Class<Map<String, Object>> inputType() { return (Class) Map.class; }
    @Override public Class<Map<String, Object>> outputType() { return (Class) Map.class; }
}
```

Use `ide_create_file`.

- [ ] **Step 6: Write McpWorkerFunction test**

```java
package io.casehub.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.casehub.engine.common.internal.auth.AuthConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpWorkerFunctionTest {

    @Test
    void stdioTransportRequiresNonEmptyCommand() {
        assertThatThrownBy(() -> new McpTransport.Stdio(List.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stdioTransportDefaultsEnvToEmptyMap() {
        var transport = new McpTransport.Stdio(List.of("/path/to/server"), null);
        assertThat(transport.env()).isEmpty();
    }

    @Test
    void httpTransportDefaultsAuthToNone() {
        var transport = new McpTransport.Http("https://example.com/mcp", null);
        assertThat(transport.auth()).isEqualTo(AuthConfig.NONE);
    }

    @Test
    void functionCarriesTransportAndToolName() {
        var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
        var fn = new McpWorkerFunction(transport, "read-file");
        assertThat(fn.transport()).isEqualTo(transport);
        assertThat(fn.toolName()).isEqualTo("read-file");
        assertThat(fn.inputType()).isEqualTo(Map.class);
        assertThat(fn.outputType()).isEqualTo(Map.class);
    }
}
```

Use `ide_create_file`.

- [ ] **Step 7: Verify module compiles and tests pass**

Run: `/opt/homebrew/bin/mvn install -Dmaven.test.skip=true -q -pl common,api -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Then: `/opt/homebrew/bin/mvn test -pl mcp -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: Tests pass.

- [ ] **Step 8: Commit**

```
feat(#831): scaffold casehub-engine-mcp module with data types

McpTransport sealed interface (Stdio, Http). McpWorkerFunction record
carrying transport + toolName. MCP SDK dependency, Jandex, test infra.

Refs #831
```

---

### Task 4: McpClientRegistry — connection management

Per-server connection pooling with thread-safe initialization, session handshake, and shutdown.

**Files:**
- Create: `mcp/src/main/java/io/casehub/engine/mcp/McpClientRegistry.java`
- Test: `mcp/src/test/java/io/casehub/engine/mcp/McpClientRegistryTest.java`

**Interfaces:**
- Consumes: `McpTransport` (from Task 3)
- Produces: `McpClientRegistry.getOrCreate(McpTransport) → McpSyncClient` — consumed by Tasks 5, 6

- [ ] **Step 1: Write registry test**

```java
package io.casehub.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.casehub.engine.common.internal.auth.AuthConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientRegistryTest {

    private final McpClientRegistry registry = new McpClientRegistry();

    @Test
    void getOrCreateReturnsSameClientForSameStdioCommand() {
        var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
        var client1 = registry.getOrCreate(transport);
        var client2 = registry.getOrCreate(transport);
        assertThat(client1).isSameAs(client2);
    }

    @Test
    void getOrCreateReturnsDifferentClientsForDifferentCommands() {
        var t1 = new McpTransport.Stdio(List.of("/bin/server1"), Map.of());
        var t2 = new McpTransport.Stdio(List.of("/bin/server2"), Map.of());
        var c1 = registry.getOrCreate(t1);
        var c2 = registry.getOrCreate(t2);
        assertThat(c1).isNotSameAs(c2);
    }

    @Test
    void getOrCreateThrowsOnAuthConflictForSameUrl() {
        var t1 = new McpTransport.Http("https://example.com", AuthConfig.NONE);
        registry.getOrCreate(t1);

        var t2 = new McpTransport.Http("https://example.com",
            new AuthConfig(AuthConfig.AuthType.BEARER, "key"));
        assertThatThrownBy(() -> registry.getOrCreate(t2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evictRemovesCachedClient() {
        var transport = new McpTransport.Stdio(List.of("/bin/server"), Map.of());
        var c1 = registry.getOrCreate(transport);
        registry.evict(transport);
        var c2 = registry.getOrCreate(transport);
        assertThat(c1).isNotSameAs(c2);
    }
}
```

Note: these tests require the MCP SDK to create real `McpSyncClient` instances. The registry constructor creates SDK clients, which for stdio transport will try to start a subprocess. Tests may need a mock transport or a test server binary. If SDK construction fails without a real server, use a mock wrapper pattern — defer this during implementation.

- [ ] **Step 2: Implement McpClientRegistry**

Use `ide_create_file`. Key design:
- `ConcurrentHashMap<String, McpSyncClient>` keyed by transport identity
- `computeIfAbsent` with factory that creates `McpSyncClient` via `McpClient.sync(transport).build()` and calls `initialize()`
- Transport key: `"stdio:" + String.join(" ", command)` or `"http:" + url`
- Auth/env conflict detection maps (same pattern as `A2AClientRegistry`)
- `@Observes ShutdownEvent` closes all clients

- [ ] **Step 3: Run tests**

Run: `/opt/homebrew/bin/mvn test -pl mcp -Dtest=McpClientRegistryTest -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: PASS

- [ ] **Step 4: Commit**

```
feat(#831): McpClientRegistry — per-server connection pooling

Thread-safe initialization via computeIfAbsent. Auth/env conflict
detection. Session handshake on first use. Shutdown cleanup.

Refs #831
```

---

### Task 5: McpWorkerFunctionProvider — discovery + YAML parsing

Detects `mcp:` blocks, connects to servers, discovers tools, returns `DiscoveredWorker` list.

**Files:**
- Create: `mcp/src/main/java/io/casehub/engine/mcp/McpWorkerFunctionProvider.java`
- Test: `mcp/src/test/java/io/casehub/engine/mcp/McpWorkerFunctionProviderTest.java`

**Interfaces:**
- Consumes: `McpTransport` (Task 3), `DiscoveredWorker` (Task 2), `McpClientRegistry` (Task 4)
- Produces: `McpWorkerFunctionProvider` CDI bean — YAML detection, tool discovery, worker creation

- [ ] **Step 1: Write provider test**

Test `handles()`, `discoverWorkers()` with mock MCP server (HTTP transport via MockWebServer serving `tools/list` JSON-RPC response).

- [ ] **Step 2: Implement McpWorkerFunctionProvider**

Key logic:
1. `handles()` — `rawWorkerNode.has("mcp")`
2. `create()` — `throw new UnsupportedOperationException("Use discoverWorkers()")`
3. `discoverWorkers()`:
   - Parse transport config from YAML `mcp:` block
   - Create temporary `McpSyncClient`, call `initialize()`, call `listTools()`
   - If YAML has explicit `capabilities:`, filter discovered tools
   - For each tool: create `DiscoveredWorker(workerName, capability, McpWorkerFunction)`
   - Close discovery client
4. Worker naming: single tool → base name; multiple → `baseName--toolName`

- [ ] **Step 3: Run tests**

Run: `/opt/homebrew/bin/mvn test -pl mcp -Dtest=McpWorkerFunctionProviderTest -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: PASS

- [ ] **Step 4: Commit**

```
feat(#831): McpWorkerFunctionProvider — tool discovery from MCP servers

Connects at definition build time, discovers tools via tools/list,
creates DiscoveredWorker per tool. Supports explicit capability filter.

Refs #831
```

---

### Task 6: McpWorkerFunctionHandler — tool execution

Executes MCP tool calls via `callTool()`, maps output, handles errors.

**Files:**
- Create: `mcp/src/main/java/io/casehub/engine/mcp/McpWorkerFunctionHandler.java`
- Test: `mcp/src/test/java/io/casehub/engine/mcp/McpWorkerFunctionHandlerTest.java`

**Interfaces:**
- Consumes: `McpClientRegistry` (Task 4), `McpWorkerFunction` (Task 3), `HandlerResult` (from A2A branch)
- Produces: `McpWorkerFunctionHandler` CDI bean — tool execution with timeout, cancellation, metadata

- [ ] **Step 1: Write handler tests**

Tests with MockWebServer serving MCP JSON-RPC responses:
- `supportsMcpWorkerFunction` / `doesNotSupportOtherFunctions`
- `callToolReturnsCompletedResult` — text content parsed as JSON
- `callToolReturnsFailedOnIsError` — `isError=true` in result
- `callToolHandlesNonJsonTextContent` — plain text fallback
- `transientErrorPropagatesAsException` — IOException propagation
- `protocolMetadataIncludesMcpFields` — mcpServer, mcpTool, mcpTransport, mcpDuration
- `timeoutReturnsExpired` — Future.get timeout

- [ ] **Step 2: Implement McpWorkerFunctionHandler**

Key logic:
1. `supports()` — `function instanceof McpWorkerFunction`
2. `execute()`:
   - Get `McpSyncClient` from registry via `mcp.transport()`
   - Submit `callTool()` to virtual thread
   - `Future.get(timeoutMs)` with timeout handling
   - On timeout: `cancel(true)`, send MCP `notifications/cancelled`, return `WorkerResult.expired()`
   - On IOException: propagate (transient → retry)
   - Convert `CallToolResult` → `Map<String, Object>` → `HandlerResult`
3. Output mapping:
   - `isError=true` → `WorkerResult.failed(concatenated text)`
   - Text content → JSON parse or `{"text": "..."}`
   - Image/resource → numbered keys
4. Protocol metadata: `mcpServer`, `mcpTool`, `mcpTransport`, `mcpDuration`

- [ ] **Step 3: Run tests**

Run: `/opt/homebrew/bin/mvn test -pl mcp -Dtest=McpWorkerFunctionHandlerTest -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: PASS

- [ ] **Step 4: Commit**

```
feat(#831): McpWorkerFunctionHandler — MCP tool execution

callTool via McpSyncClient on virtual threads. Timeout enforcement,
cancellation notification, output mapping, protocol metadata.

Refs #831
```

---

### Task 7: Integration test + CLAUDE.md

End-to-end `@QuarkusTest` validating the full case lifecycle via MockWebServer, plus CLAUDE.md documentation.

**Files:**
- Create: `mcp/src/test/java/io/casehub/engine/mcp/McpWorkerIntegrationTest.java`
- Modify: `CLAUDE.md` — add casehub-engine-mcp module section

**Interfaces:**
- Consumes: All types from Tasks 1-6

- [ ] **Step 1: Write integration test**

Same pattern as `A2AWorkerIntegrationTest`:
- `@QuarkusTest` with `@QuarkusTestResource` for MockWebServer
- Inner `CaseHub` subclass with MCP worker (HTTP transport)
- MockWebServer serves MCP `tools/list` (for discovery) and `tools/call` (for execution) JSON-RPC responses
- Case start → binding fires → handler calls MCP tool → output in context → goal evaluates → case completes

Note: discovery happens at definition build time (CDI startup), so MockWebServer must serve `tools/list` during `@QuarkusTestResource.start()`. The `tools/call` response is enqueued per test.

- [ ] **Step 2: Run integration test**

Run: `TESTCONTAINERS_RYUK_DISABLED=true /opt/homebrew/bin/mvn test -pl mcp -Dtest=McpWorkerIntegrationTest -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: PASS

- [ ] **Step 3: Run full MCP test suite**

Run: `/opt/homebrew/bin/mvn test -pl mcp -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: All tests pass

- [ ] **Step 4: Update CLAUDE.md**

Add `casehub-engine-mcp Module` section after the `casehub-engine-a2a Module` section. Document core types, YAML schema, discovery model, execution model, and testing.

- [ ] **Step 5: Run cross-module tests**

Run: `/opt/homebrew/bin/mvn test -pl common,a2a,mcp -f /Users/mdproctor/claude/casehub/worktrees/67/engine/pom.xml`
Expected: All tests pass across all affected modules

- [ ] **Step 6: Commit**

```
feat(#831): MCP integration test and CLAUDE.md documentation

End-to-end QuarkusTest with MockWebServer validates full case lifecycle.
Document casehub-engine-mcp module in CLAUDE.md.

Refs #831
```
