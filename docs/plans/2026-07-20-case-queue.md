# Case Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #730 — feat: case queue implementation — CaseQueueEntry, CaseQueueService, CaseQueueRoutingStrategy SPI
**Issue group:** #730

**Goal:** Build an operational case queue layer on platform's subject view toolkit and labelling infrastructure. Label rules on CaseDefinition classify cases; SubjectViewOrchestrator determines queue membership; CaseQueueService provides claim/release/escalate operations.

**Architecture:** New `casehub-engine-queue` module (`@Alternative @Priority(10)`, classpath-activated, same pattern as `casehub-blackboard`). CaseLabelEvaluator observes CaseLifecycleEvent, evaluates LabelRule conditions, updates CaseInstance labels, delegates to SubjectViewOrchestrator for queue membership, fires CaseQueueEvent. CaseQueueEntryManager creates/deletes/revokes CaseQueueEntry records. CaseQueueService provides operational actions.

**Tech Stack:** Quarkus CDI, platform-api (LabelRule, LabelAction, SubjectViewOrchestrator, SubjectViewSpec), engine-common (CaseInstance, CaseLifecycleEvent), Jackson (context conversion)

## Global Constraints

- `casehub-engine-queue` is an optional module — all CDI beans use `@ApplicationScoped` (no `@DefaultBean` needed since module is classpath-activated)
- Platform-view dependencies (`SubjectViewOrchestrator`, `ViewMembershipTracker`) stay in the queue module — never in engine runtime
- `LabelRule.evaluate()` is platform's static method — engine does not reimplement label evaluation
- All labels on CaseInstance are rule-derived in v1 — clean-slate recomputation on every evaluation
- `@ObservesAsync` for CaseLifecycleEvent (not `@Observes`) — engine fires via `Event.fireAsync()`
- Tests use `casehub-persistence-memory` and `casehub-platform-view-inmem` — no Docker

---

### Task 1: Data Model — CaseInstance labels and CaseDefinition labelRules

Add `Set<String> labels` to CaseInstance and `List<LabelRule> labelRules` to CaseDefinition with builder support.

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/common/internal/model/CaseInstance.java`
- Modify: `api/src/main/java/io/casehub/api/model/CaseDefinition.java` (class + Builder)
- Modify: `persistence-hibernate/src/main/java/io/casehub/persistence/jpa/CaseInstanceEntity.java` (add @ElementCollection)
- Modify: `persistence-memory/src/main/java/io/casehub/persistence/memory/InMemoryCaseInstanceRepository.java` (copy labels on save/update)
- Modify: `persistence-memory/src/main/java/io/casehub/persistence/memory/InMemoryReactiveCaseInstanceRepository.java` (delegate)
- Test: `common/src/test/java/io/casehub/engine/common/internal/model/CaseInstanceLabelsTest.java`
- Test: `api/src/test/java/io/casehub/api/model/CaseDefinitionLabelRuleTest.java`

**Interfaces:**
- Produces: `CaseInstance.getLabels(): Set<String>`, `CaseInstance.setLabels(Set<String>)`, `CaseDefinition.getLabelRules(): List<LabelRule>`, `CaseDefinition.setLabelRules(List<LabelRule>)`, `CaseDefinition.Builder.labelRule(LabelRule): Builder`, `CaseDefinition.Builder.labelRules(List<LabelRule>): Builder`

- [ ] **Step 1: Write CaseInstance labels test**

```java
// common/src/test/java/.../CaseInstanceLabelsTest.java
class CaseInstanceLabelsTest {
    @Test void labels_empty_by_default() {
        CaseInstance ci = new CaseInstance();
        assertThat(ci.getLabels()).isEmpty();
    }
    @Test void labels_mutable() {
        CaseInstance ci = new CaseInstance();
        ci.getLabels().add("priority/high");
        assertThat(ci.getLabels()).containsExactly("priority/high");
    }
    @Test void setLabels_replaces() {
        CaseInstance ci = new CaseInstance();
        ci.getLabels().add("old");
        ci.setLabels(new LinkedHashSet<>(Set.of("new/a", "new/b")));
        assertThat(ci.getLabels()).containsExactlyInAnyOrder("new/a", "new/b");
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Run: `mvn test -pl common -Dtest="CaseInstanceLabelsTest" -q`
Expected: FAIL — `getLabels()` / `setLabels()` don't exist yet

- [ ] **Step 3: Add labels field to CaseInstance**

Use `ide_insert_member` to add after the `pendingActionGate` field:
```java
private Set<String> labels = new LinkedHashSet<>();

public Set<String> getLabels() { return labels; }
public void setLabels(Set<String> labels) { this.labels = labels; }
```
Add `import java.util.LinkedHashSet;` and `import java.util.Set;`.

- [ ] **Step 4: Run test — verify PASS**

Run: `mvn test -pl common -Dtest="CaseInstanceLabelsTest" -q`
Expected: PASS

- [ ] **Step 5: Write CaseDefinition labelRules test**

```java
// api/src/test/java/.../CaseDefinitionLabelRuleTest.java
class CaseDefinitionLabelRuleTest {
    @Test void labelRules_empty_by_default() {
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test").name("test").version("1.0").build();
        assertThat(def.getLabelRules()).isEmpty();
    }
    @Test void labelRule_builder_single() {
        LabelRule rule = new LabelRule("r1",
            (CompiledExpression<Map<String, Object>, Boolean>) ctx -> Boolean.TRUE,
            List.of(new LabelAction.Add("priority/high")));
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test").name("test").version("1.0")
            .labelRule(rule).build();
        assertThat(def.getLabelRules()).hasSize(1);
        assertThat(def.getLabelRules().get(0).name()).isEqualTo("r1");
    }
    @Test void labelRules_builder_list() {
        LabelRule r1 = new LabelRule("r1",
            (CompiledExpression<Map<String, Object>, Boolean>) ctx -> Boolean.TRUE,
            List.of(new LabelAction.Add("a")));
        LabelRule r2 = new LabelRule("r2",
            (CompiledExpression<Map<String, Object>, Boolean>) ctx -> Boolean.FALSE,
            List.of(new LabelAction.Remove("a")));
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test").name("test").version("1.0")
            .labelRules(List.of(r1, r2)).build();
        assertThat(def.getLabelRules()).hasSize(2);
    }
    @Test void labelRules_immutable_copy() {
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test").name("test").version("1.0").build();
        assertThatThrownBy(() -> def.getLabelRules().add(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 6: Run test — verify FAIL**

Run: `mvn test -pl api -Dtest="CaseDefinitionLabelRuleTest" -q`
Expected: FAIL — `getLabelRules()` / `labelRule()` don't exist yet

- [ ] **Step 7: Add labelRules to CaseDefinition**

Use `ide_insert_member` to add the field after `signals`:
```java
private List<LabelRule> labelRules = List.of();
```

Add getter/setter:
```java
public List<LabelRule> getLabelRules() { return labelRules; }
public void setLabelRules(List<LabelRule> labelRules) {
    this.labelRules = List.copyOf(labelRules);
}
```

Add Builder field and methods:
```java
// field
private List<LabelRule> labelRules = new ArrayList<>();

// methods
public Builder labelRule(LabelRule rule) {
    this.labelRules.add(Objects.requireNonNull(rule));
    return this;
}
public Builder labelRules(List<LabelRule> rules) {
    this.labelRules = new ArrayList<>(rules);
    return this;
}
```

In `Builder.build()`, add `def.setLabelRules(labelRules);` alongside the other setter calls.

Add imports: `io.casehub.platform.api.label.LabelRule`.

- [ ] **Step 8: Run test — verify PASS**

Run: `mvn test -pl api -Dtest="CaseDefinitionLabelRuleTest" -q`
Expected: PASS

- [ ] **Step 9: Add @ElementCollection to CaseInstanceEntity**

Use `ide_insert_member` to add after existing fields in `CaseInstanceEntity`:
```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "case_instance_label",
    joinColumns = @JoinColumn(name = "case_instance_id"))
@Column(name = "label")
private Set<String> labels = new LinkedHashSet<>();
```

Add getter/setter and wire into `toModel()` / `fromModel()` mapping methods (copy labels between entity and domain model).

- [ ] **Step 10: Update InMemory persistence to copy labels on save/update**

In `InMemoryCaseInstanceRepository`, ensure `save()` and `update()` copy the labels set (defensive copy so mutations don't leak between stored and live instances). Same for the reactive delegate.

- [ ] **Step 11: Commit**

```bash
git add common/src api/src persistence-hibernate/src persistence-memory/src
git commit -m "feat(#730): add labels to CaseInstance and labelRules to CaseDefinition

Refs #730"
```

---

### Task 2: YAML LabelRule Parsing

Parse `labelRules:` YAML block into `List<LabelRule>` on CaseDefinition via CaseDefinitionYamlMapper.

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`
- Test: `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperLabelRuleTest.java`

**Interfaces:**
- Consumes: `CaseDefinition.setLabelRules(List<LabelRule>)` from Task 1
- Produces: YAML parsing — `labelRules:` block with `name`, `when`, `actions` (each `add:` or `remove:`)

- [ ] **Step 1: Write YAML parsing test**

```java
// api/src/test/java/.../CaseDefinitionYamlMapperLabelRuleTest.java
class CaseDefinitionYamlMapperLabelRuleTest {
    @Test void parsesLabelRulesFromYaml() {
        String yaml = """
            namespace: test
            name: test-case
            version: "1.0"
            labelRules:
              - name: high-priority
                when: '.severity == "HIGH"'
                actions:
                  - add: "priority/high"
              - name: resolved
                when: '.status == "resolved"'
                actions:
                  - remove: "triage/pending"
                  - add: "resolved/done"
            """;
        CaseDefinition def = CaseDefinitionYamlMapper.fromYaml(yaml);
        assertThat(def.getLabelRules()).hasSize(2);
        LabelRule rule0 = def.getLabelRules().get(0);
        assertThat(rule0.name()).isEqualTo("high-priority");
        assertThat(rule0.actions()).hasSize(1);
        assertThat(rule0.actions().get(0)).isInstanceOf(LabelAction.Add.class);
        assertThat(((LabelAction.Add) rule0.actions().get(0)).label()).isEqualTo("priority/high");

        LabelRule rule1 = def.getLabelRules().get(1);
        assertThat(rule1.name()).isEqualTo("resolved");
        assertThat(rule1.actions()).hasSize(2);
        assertThat(rule1.actions().get(0)).isInstanceOf(LabelAction.Remove.class);
        assertThat(rule1.actions().get(1)).isInstanceOf(LabelAction.Add.class);
    }

    @Test void noLabelRules_returnsEmptyList() {
        String yaml = """
            namespace: test
            name: test-case
            version: "1.0"
            """;
        CaseDefinition def = CaseDefinitionYamlMapper.fromYaml(yaml);
        assertThat(def.getLabelRules()).isEmpty();
    }

    @Test void labelRuleCondition_evaluates() {
        String yaml = """
            namespace: test
            name: test-case
            version: "1.0"
            labelRules:
              - name: high-priority
                when: '.severity == "HIGH"'
                actions:
                  - add: "priority/high"
            """;
        CaseDefinition def = CaseDefinitionYamlMapper.fromYaml(yaml);
        LabelRule rule = def.getLabelRules().get(0);
        List<LabelAction> actions = LabelRule.evaluate(
            List.of(rule), Map.of("severity", "HIGH"));
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("priority/high");
    }
}
```

- [ ] **Step 2: Run test — verify FAIL**

Run: `mvn test -pl api -Dtest="CaseDefinitionYamlMapperLabelRuleTest" -q`
Expected: FAIL — YAML key `labelRules` not handled

- [ ] **Step 3: Implement YAML parsing in CaseDefinitionYamlMapper**

In `CaseDefinitionYamlMapper`, find the method that processes YAML nodes (likely `fromYaml()` or a dispatch method). Add handling for the `labelRules` key:

```java
// Inside the node processing method, add a case for "labelRules":
if (root.has("labelRules")) {
    JsonNode rulesNode = root.get("labelRules");
    List<LabelRule> rules = new ArrayList<>();
    for (JsonNode ruleNode : rulesNode) {
        String name = ruleNode.get("name").asText();
        String when = ruleNode.get("when").asText();
        CompiledExpression<Map<String, Object>, Boolean> condition =
            expressionEngineRegistry.compile("jq", when, Map.class, Boolean.class);
        List<LabelAction> actions = new ArrayList<>();
        for (JsonNode actionNode : ruleNode.get("actions")) {
            if (actionNode.has("add")) {
                actions.add(new LabelAction.Add(actionNode.get("add").asText()));
            } else if (actionNode.has("remove")) {
                actions.add(new LabelAction.Remove(actionNode.get("remove").asText()));
            }
        }
        rules.add(new LabelRule(name, condition, actions));
    }
    definition.setLabelRules(rules);
}
```

Note: Check how `CaseDefinitionYamlMapper` accesses `ExpressionEngineRegistry` — it may use a static reference or be injected. Follow the existing pattern for JQ compilation (used by binding conditions, goals, milestones).

- [ ] **Step 4: Run test — verify PASS**

Run: `mvn test -pl api -Dtest="CaseDefinitionYamlMapperLabelRuleTest" -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api/src
git commit -m "feat(#730): parse labelRules from YAML in CaseDefinitionYamlMapper

Refs #730"
```

---

### Task 3: Module Scaffold — casehub-engine-queue

Create the new Maven module with dependencies, test configuration, and the event types.

**Files:**
- Create: `queue/pom.xml`
- Modify: `pom.xml` (root — add `<module>queue</module>`)
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEvent.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEventType.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEntryClaimed.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEntryReleased.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEntryEscalated.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/event/CaseQueueEntryRevoked.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/model/CaseQueueEntry.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/model/QueueEntryStatus.java`
- Create: `queue/src/test/resources/application.properties`
- Test: `queue/src/test/java/io/casehub/engine/queue/event/CaseQueueEventTypeTest.java`

**Interfaces:**
- Produces: `CaseQueueEvent(UUID caseId, UUID queueViewId, String queueName, CaseQueueEventType eventType, String tenancyId)`, `CaseQueueEventType` enum (ADDED, REMOVED, CHANGED), `CaseQueueEntry` entity, `QueueEntryStatus` enum (PENDING, CLAIMED, REVOKED), operational CDI event records

- [ ] **Step 1: Create pom.xml for queue module**

Mirror blackboard's pom.xml structure. Dependencies:
- `casehub-engine-api` (compile)
- `casehub-engine` (compile — for runtime handler classes)
- `casehub-platform-view` (compile — SubjectViewOrchestrator)
- `casehub-engine-persistence-memory` (test)
- `casehub-platform-view-inmem` (test)
- `casehub-engine-scheduler-quartz` (test)
- `quarkus-junit5` (test)
- `quarkus-jdbc-h2` (test)
- `casehub-ledger` + `casehub-engine-ledger` + `casehub-ledger-testing` (test)
- `assertj-core` (test)
- `awaitility` (test)

ArtifactId: `casehub-engine-queue`

- [ ] **Step 2: Add `<module>queue</module>` to root pom.xml**

Use `ide_replace_text_in_file` to add after `<module>flow</module>`.

- [ ] **Step 3: Create CaseQueueEventType enum**

```java
package io.casehub.engine.queue.event;

import io.casehub.platform.api.view.ViewEventType;

public enum CaseQueueEventType {
    ADDED, REMOVED, CHANGED;

    public static CaseQueueEventType from(ViewEventType viewType) {
        return switch (viewType) {
            case ADDED -> ADDED;
            case REMOVED -> REMOVED;
            case CHANGED -> CHANGED;
        };
    }
}
```

- [ ] **Step 4: Create CaseQueueEvent record**

```java
package io.casehub.engine.queue.event;

import java.util.UUID;

public record CaseQueueEvent(
    UUID caseId,
    UUID queueViewId,
    String queueName,
    CaseQueueEventType eventType,
    String tenancyId
) {}
```

- [ ] **Step 5: Create QueueEntryStatus enum**

```java
package io.casehub.engine.queue.model;

public enum QueueEntryStatus { PENDING, CLAIMED, REVOKED }
```

- [ ] **Step 6: Create CaseQueueEntry entity**

Full JPA entity as specified in the design spec — `@Entity`, `@Table` with unique constraint on `(caseId, viewId)`, all fields including `previousViewId`, `previousViewName`, `escalatedAt`.

- [ ] **Step 7: Create operational CDI event records**

Four records in the event package: `CaseQueueEntryClaimed`, `CaseQueueEntryReleased`, `CaseQueueEntryEscalated`, `CaseQueueEntryRevoked`.

- [ ] **Step 8: Create test application.properties**

Mirror blackboard's pattern. Add index-dependency entries for `casehub-platform-view` and `casehub-platform-view-inmem`. Add `InMemoryViewMembershipTracker` and `InMemorySubjectViewStore` to selected-alternatives.

- [ ] **Step 9: Write CaseQueueEventType test**

```java
class CaseQueueEventTypeTest {
    @Test void mapsAllViewEventTypes() {
        assertThat(CaseQueueEventType.from(ViewEventType.ADDED)).isEqualTo(CaseQueueEventType.ADDED);
        assertThat(CaseQueueEventType.from(ViewEventType.REMOVED)).isEqualTo(CaseQueueEventType.REMOVED);
        assertThat(CaseQueueEventType.from(ViewEventType.CHANGED)).isEqualTo(CaseQueueEventType.CHANGED);
    }
}
```

- [ ] **Step 10: Run test — verify PASS**

Run: `mvn install -DskipTests -q && mvn test -pl queue -Dtest="CaseQueueEventTypeTest" -q`
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add queue/ pom.xml
git commit -m "feat(#730): scaffold casehub-engine-queue module with event types and CaseQueueEntry

Refs #730"
```

---

### Task 4: CaseQueueEntryStore SPI and In-Memory Implementation

Persistence SPI and in-memory implementation for CaseQueueEntry.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/spi/CaseQueueEntryStore.java`
- Create: `queue/src/main/java/io/casehub/engine/queue/store/InMemoryCaseQueueEntryStore.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/store/InMemoryCaseQueueEntryStoreTest.java`

**Interfaces:**
- Consumes: `CaseQueueEntry`, `QueueEntryStatus` from Task 3
- Produces: `CaseQueueEntryStore` interface with `save()`, `upsertByCaseAndView()`, `findById()`, `findByCaseAndView()`, `findByView()`, `findByCaseId()`, `countByView()`, `delete()`, `deleteByCaseId()`, `claimIfPending()`

- [ ] **Step 1: Write store tests**

Test all SPI methods: save, find, upsert idempotency, claimIfPending atomicity (PENDING → CLAIMED succeeds, non-PENDING returns empty), countByView, delete, deleteByCaseId.

- [ ] **Step 2: Run tests — verify FAIL**

- [ ] **Step 3: Create CaseQueueEntryStore interface**

All methods as specified in the design spec.

- [ ] **Step 4: Create InMemoryCaseQueueEntryStore**

`@ApplicationScoped` with `ConcurrentHashMap<UUID, CaseQueueEntry>`. `claimIfPending()` uses `synchronized` on the entry for atomicity. `upsertByCaseAndView()` finds by `(caseId, viewId)` and replaces or inserts.

- [ ] **Step 5: Run tests — verify PASS**

- [ ] **Step 6: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseQueueEntryStore SPI and InMemory implementation

Refs #730"
```

---

### Task 5: CaseQueueViewManager

Wraps SubjectViewOrchestrator for queue view CRUD with deterministic UUIDs.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/view/CaseQueueViewManager.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/view/CaseQueueViewManagerTest.java`

**Interfaces:**
- Consumes: `SubjectViewOrchestrator.saveView()`, `SubjectViewOrchestrator.deleteView()` from platform
- Produces: `CaseQueueViewManager.ensureQueueView(String name, String tenancyId, String labelPattern): SubjectViewSpec`, `CaseQueueViewManager.deleteQueueView(UUID viewId): boolean`

- [ ] **Step 1: Write view manager test**

Test idempotency: calling `ensureQueueView()` twice with same (name, tenancyId) produces same UUID. Test deletion.

- [ ] **Step 2: Run test — verify FAIL**

- [ ] **Step 3: Implement CaseQueueViewManager**

As specified in design spec — deterministic UUID via `UUID.nameUUIDFromBytes()`, delegates to `SubjectViewOrchestrator`.

- [ ] **Step 4: Run test — verify PASS**

- [ ] **Step 5: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseQueueViewManager with deterministic UUIDs

Refs #730"
```

---

### Task 6: CaseLabelEvaluator — Core Label Evaluation

The central observer that evaluates label rules on CaseLifecycleEvent and fires CaseQueueEvent.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/label/CaseLabelEvaluator.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/label/CaseLabelEvaluatorTest.java`

**Interfaces:**
- Consumes: `CaseLifecycleEvent` (CDI @ObservesAsync), `CaseDefinitionRegistry.getCaseDefinition()`, `LabelRule.evaluate()`, `SubjectViewOrchestrator.evaluateAndTrack()`, `ReactiveCaseInstanceRepository.update()`
- Produces: `CaseQueueEvent` (CDI Event.fire())

- [ ] **Step 1: Write evaluator tests**

Unit tests with mocks (same reflection injection pattern as `MilestoneActivatedEventHandlerTest`):
1. `labelRules_applied_and_labels_updated` — context matches rule, labels set on instance, `evaluateAndTrack()` called, `CaseQueueEvent` fired
2. `no_labelRules_skips_evaluation` — definition has no rules, no orchestrator call
3. `labels_unchanged_skips_orchestrator` — rules evaluate to same labels, no `evaluateAndTrack()` call
4. `terminal_status_clears_labels` — COMPLETED/FAULTED/CANCELLED clears labels and calls `evaluateAndTrack()` with empty set
5. `clean_slate_recomputation` — existing labels cleared before applying new rules
6. `remove_action_negates_add` — rule order: first adds, second removes — final set reflects removal

- [ ] **Step 2: Run tests — verify FAIL**

- [ ] **Step 3: Implement CaseLabelEvaluator**

`@ApplicationScoped` with `@ObservesAsync CaseLifecycleEvent`. Per-case locking via `ConcurrentHashMap<UUID, ReentrantLock>`. Full flow as documented in the design spec:
1. Acquire per-case lock
2. Re-read CaseInstance from repository (latest version)
3. Resolve CaseDefinition via CaseDefinitionRegistry
4. Guard: no label rules → return
5. Convert `contextSnapshot` to `Map<String, Object>` via `ObjectMapper.convertValue()`
6. Call `LabelRule.evaluate(rules, context)` → `List<LabelAction>`
7. Clear labels, apply Add/Remove in order
8. Compare before/after
9. If changed: persist via `update().await().indefinitely()`, call `evaluateAndTrack()`, map events via `CaseQueueEventType.from()`, fire `CaseQueueEvent`
10. Terminal status: clear labels, `evaluateAndTrack()`, remove lock entry

- [ ] **Step 4: Run tests — verify PASS**

- [ ] **Step 5: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseLabelEvaluator — label rule evaluation on CaseLifecycleEvent

Refs #730"
```

---

### Task 7: CaseQueueEntryManager — Queue Entry Lifecycle

Observes CaseQueueEvent and manages CaseQueueEntry records.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/entry/CaseQueueEntryManager.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/entry/CaseQueueEntryManagerTest.java`

**Interfaces:**
- Consumes: `CaseQueueEvent` (CDI @Observes), `CaseQueueEntryStore.upsertByCaseAndView()`, `CaseQueueEntryStore.findByCaseAndView()`, `CaseQueueEntryStore.delete()`
- Produces: `CaseQueueEntryRevoked` (CDI Event.fireAsync())

- [ ] **Step 1: Write entry manager tests**

1. `added_creates_pending_entry` — ADDED event creates new PENDING CaseQueueEntry
2. `added_existing_pending_noop` — ADDED on existing PENDING is no-op
3. `added_existing_claimed_noop` — ADDED on existing CLAIMED is no-op (don't disrupt claim)
4. `added_existing_revoked_reactivates` — ADDED on REVOKED re-activates to PENDING
5. `removed_pending_deletes` — REMOVED on PENDING deletes the entry
6. `removed_claimed_revokes` — REMOVED on CLAIMED transitions to REVOKED and fires `CaseQueueEntryRevoked`
7. `changed_noop` — CHANGED event does nothing in v1

- [ ] **Step 2: Run tests — verify FAIL**

- [ ] **Step 3: Implement CaseQueueEntryManager**

`@ApplicationScoped`. Uses `@Observes CaseQueueEvent` (synchronous — events fired within the evaluator's lock). ADDED: `upsertByCaseAndView()` with status logic. REMOVED: check existing status, delete if PENDING, revoke if CLAIMED.

- [ ] **Step 4: Run tests — verify PASS**

- [ ] **Step 5: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseQueueEntryManager — ADDED/REMOVED/CHANGED lifecycle

Refs #730"
```

---

### Task 8: CaseQueueService — Operational Actions

Claim, release, escalate operations with tenancy enforcement.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/service/CaseQueueService.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/service/CaseQueueServiceTest.java`

**Interfaces:**
- Consumes: `CaseQueueEntryStore.claimIfPending()`, `CaseQueueEntryStore.findById()`, `CaseQueueEntryStore.findByCaseAndView()`, `CaseQueueEntryStore.save()`, `CaseQueueEntryStore.findByView()`, `CaseQueueEntryStore.countByView()`
- Produces: `CaseQueueService.claim(UUID, String, String): CaseQueueEntry`, `CaseQueueService.release(UUID, String): CaseQueueEntry`, `CaseQueueService.escalate(UUID, String, UUID): CaseQueueEntry`, `CaseQueueService.findPending(UUID, String): List<CaseQueueEntry>`, `CaseQueueService.countByView(UUID, String): long`

- [ ] **Step 1: Write service tests**

1. `claim_pending_succeeds` — PENDING → CLAIMED, sets assignedTo/claimedAt
2. `claim_nonPending_throws` — already CLAIMED throws
3. `claim_wrongTenancy_throws` — tenancy mismatch throws `IllegalArgumentException`
4. `release_claimed_succeeds` — CLAIMED → PENDING, clears assignedTo/claimedAt
5. `release_notClaimed_throws` — PENDING entry throws (can't release what's not claimed)
6. `escalate_moves_entry` — updates viewId/viewName, sets previousViewId, clears claim, PENDING status
7. `escalate_alreadyInTarget_throws` — case already in target queue throws `IllegalStateException`
8. `findPending_filtersCorrectly` — returns only PENDING entries for the given view
9. `countByView_correct` — counts entries for the given view

- [ ] **Step 2: Run tests — verify FAIL**

- [ ] **Step 3: Implement CaseQueueService**

`@ApplicationScoped`. All methods enforce tenancy. `claim()` delegates to `claimIfPending()`. `release()` loads entry, validates CLAIMED, transitions to PENDING. `escalate()` is a move — checks target doesn't already have the case, updates fields. Fires operational CDI events via `Event.fireAsync()`.

- [ ] **Step 4: Run tests — verify PASS**

- [ ] **Step 5: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseQueueService — claim, release, escalate operations

Refs #730"
```

---

### Task 9: CaseLabelReconciler — Startup Recovery

Re-evaluates all active cases on startup to reconcile label state after crash.

**Files:**
- Create: `queue/src/main/java/io/casehub/engine/queue/reconcile/CaseLabelReconciler.java`
- Test: `queue/src/test/java/io/casehub/engine/queue/reconcile/CaseLabelReconcilerTest.java`

**Interfaces:**
- Consumes: `ReactiveCaseInstanceRepository.findByStatus()`, `CaseDefinitionRegistry`, `LabelRule.evaluate()`, `SubjectViewOrchestrator.evaluateAndTrack()`, `CaseQueueEntryStore`
- Produces: Startup reconciliation — no public API, `@Observes @Priority(200) StartupEvent`

- [ ] **Step 1: Write reconciler test**

Integration test using `@QuarkusTest`: create a CaseDefinition with label rules and a queue view, start a case, manually clear labels (simulating crash state), then trigger reconciliation and verify labels and queue entries are restored.

- [ ] **Step 2: Run test — verify FAIL**

- [ ] **Step 3: Implement CaseLabelReconciler**

`@ApplicationScoped`. Observes `@Priority(200) StartupEvent`. Loads active cases per tenancy, re-evaluates label rules, calls `evaluateAndTrack()`. Queue view bootstrap at `@Priority(100)` must run first.

- [ ] **Step 4: Run test — verify PASS**

- [ ] **Step 5: Commit**

```bash
git add queue/src
git commit -m "feat(#730): CaseLabelReconciler — startup crash recovery

Refs #730"
```

---

### Task 10: Integration Test — Full Lifecycle

End-to-end test: define a case with label rules + queue views, start a case, signal context changes, verify labels update, queue entries created, claim/release/escalate, terminal state cleanup.

**Files:**
- Test: `queue/src/test/java/io/casehub/engine/queue/CaseQueueLifecycleTest.java`

**Interfaces:**
- Consumes: All from Tasks 1-9

- [ ] **Step 1: Write full lifecycle @QuarkusTest**

Inner `CaseHub` subclass with:
- A capability binding with context-change trigger
- Label rules: `.severity == "HIGH"` → add `priority/high`
- Queue view: `priority/high` pattern
- Test flow: start case with `severity: "HIGH"` → assert label added → assert queue entry PENDING → claim → signal `severity: "LOW"` → assert label removed → assert entry REVOKED → verify terminal cleanup

- [ ] **Step 2: Run test — verify PASS**

Run: `mvn test -pl queue -Dtest="CaseQueueLifecycleTest" -q`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add queue/src
git commit -m "feat(#730): integration test — full case queue lifecycle

Closes #730"
```
