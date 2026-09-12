# Unified Resolution Pipeline

> From knowledge to action: how CaseHub retrieves, ranks, presents, and learns
> from every resolution — automated, human, or hybrid.

---

## The Pipeline

Every resolution follows the same path regardless of who selects and who executes:

```
Situation → Retrieve → Rank → Select → Execute → Feedback
                                                      ↓
                                              Improves retrieval
```

The variable is who selects (agent or human) and who executes (agent or human).
The pipeline, the retrieval, the ranking, and the feedback loop are the same.

## Four Quadrants

| Select | Execute | Example | Setup |
|--------|---------|---------|-------|
| Agent | Agent | CBR-routed automated investigation | `cbr:` block + capability binding (default) |
| Agent | Human | Agent picks the runbook, analyst follows it | `cbr:` block + human-executed binding |
| Human | Agent | Analyst picks from ranked candidates, engine dispatches | `judgment:` binding + capability binding |
| Human | Human | Analyst picks a procedure and follows it manually | `judgment:` binding + human task |

The automated path (Agent→Agent) is the default — just add a `cbr:` block. Each
additional quadrant adds one binding to the YAML.

---

## Knowledge Sources

The pipeline retrieves from two sources, ranked in a single list:

### Case History (automatic)

When a case reaches a terminal state (COMPLETED, FAULTED, CANCELLED),
`CbrCaseRetainObserver` stores a `ResolvedCase` with:
- Problem description and solution trace
- Extracted features (severity, category, entity type, etc.)
- Plan trace — which bindings fired, which agents executed, what outcomes
- Outcome confidence — how well the resolution worked

No configuration needed beyond the `cbr:` block. Case history accumulates
as the system processes cases.

### Knowledge Corpus (authored)

Operational knowledge — runbooks, SOPs, investigation procedures, decision
trees, reference guides — authored by domain experts and ingested into the
same CBR store.

Implement `CorpusSourceAdapter` to connect your knowledge base:

```java
@ApplicationScoped
public class RunbookAdapter implements CorpusSourceAdapter {
    @Override public String id() { return "runbooks"; }

    @Override
    public List<ResolutionGuideInput> discover(String tenancyId) {
        return loadFromKnowledgeBase().stream()
            .map(doc -> new ResolutionGuideInput(
                doc.id(),                            // stable ID for idempotent re-ingestion
                doc.problemDescription(),            // what situation this addresses
                doc.solutionProse(),                 // full resolution text
                mapSteps(doc.procedures()),          // optional structured steps
                extractFeatures(doc),                // features for similarity matching
                "soc-incidents",                     // domain — must match cbr.domain
                Map.of("source", doc.sourceUrl())))  // optional metadata
            .toList();
    }
}
```

**Feature extraction is critical.** Features determine which documents get
retrieved for which situations. A runbook about phishing with
`category: "phishing"` and `severity: "HIGH"` will surface when a case has
matching features. Without features, retrieval relies solely on semantic
similarity of the problem text — which works but is less precise.

**Change detection (optional):** Override `supportsChangeDetection()` and
`onChange()` to receive incremental updates without full re-ingestion:

```java
@Override public boolean supportsChangeDetection() { return true; }

@Override public void onChange(Consumer<CorpusChangeEvent> listener) {
    knowledgeBase.subscribe(event -> {
        switch (event.type()) {
            case CREATED -> listener.accept(new CorpusChangeEvent.Added(toInput(event.doc())));
            case UPDATED -> listener.accept(new CorpusChangeEvent.Updated(toInput(event.doc()), event.id()));
            case DELETED -> listener.accept(new CorpusChangeEvent.Removed(event.id()));
        }
    });
}
```

**Idempotent ingestion:** `ResolutionIngestionService` derives a deterministic
`caseId` from `documentId` via `UUID.nameUUIDFromBytes()`. On application
restart, re-ingestion supersedes existing entries rather than creating
duplicates.

### Corpus Metadata — Standardised Knowledge Representation

The resolution pipeline retrieves knowledge documents alongside case history.
For retrieval to work well, documents need structured metadata that
distinguishes "this runbook applies to your situation" from "this runbook
exists." The platform provides a standardised knowledge representation with
per-type metadata schemas.

#### The Two-Layer Metadata Model

Knowledge metadata operates at two layers:

**Layer 1 — Knowledge representation (garden entry format):** Standardised
document schemas for different types of operational knowledge. Each knowledge
type has a defined structure with required sections and type-specific
frontmatter fields. Six gardens cover distinct knowledge categories:

| Garden | Entry types | Key metadata fields | Editorial bar |
|--------|------------|-------------------|---------------|
| **discovery** | gotcha, technique, undocumented, convention | `symptom`, `root_cause`, `stack`, `tags`, `score` | Would a skilled developer still spend significant time? |
| **patterns** | architectural, migration, integration, testing | `suitability`, `variants`, `stability`, `observed_in` | Would a practitioner reach for something more complex? |
| **examples** | code | `stack`, minimal working example | Minimal, working, real use case? |
| **evolution** | breaking, deprecation, capability | `changed_in`, `breaking`, `migration_effort` | Would change code correctness for someone on that version? |
| **risk** | failure-mode, antipattern, incident | `severity`, `failure_pattern`, `observed_at_scale` | Caused production harm; mechanism universal enough to recur? |
| **decisions** | architecture, technology, process | alternatives considered, reasoning, consequences | Reasoning clear enough for someone facing the same choice? |

Each type carries structured body sections — a gotcha has Symptom, Root
Cause, What Was Tried, Fix, Why Non-Obvious. A risk entry has Failure Mode,
Root Cause, Mitigation, Detection. These sections become the `problem` and
`solution` fields in `ResolutionGuideInput`.

**Layer 2 — Similarity engine (CbrFeatureSchema):** Declares how metadata
is compared for retrieval. `FeatureField` types determine the similarity
algorithm per field:

| Feature type | Use for | Similarity |
|-------------|---------|------------|
| `categorical` | Tags, categories, enum values | Exact match / subsumption |
| `numeric(min, max)` | Severity scores, confidence | Range-normalised distance |
| `semanticText` | Free-form descriptions | Embedding cosine similarity |
| `categoricalList` | Multiple labels, MITRE tactics | Jaccard set similarity |
| `timeSeries` | Temporal patterns | DTW (Dynamic Time Warping) |

Layer 1 defines WHAT metadata a document carries. Layer 2 defines HOW that
metadata is compared. Both are required for effective retrieval.

#### Per-Type Feature Mapping

Different knowledge types produce different feature sets. The
`CorpusSourceAdapter` maps from the standardised entry format to CBR features:

```java
@ApplicationScoped
public class GardenCorpusAdapter implements CorpusSourceAdapter {

    @Override
    public List<ResolutionGuideInput> discover(String tenancyId) {
        return gardenReader.readEntries(tenancyId).stream()
            .map(entry -> new ResolutionGuideInput(
                entry.id(),
                extractProblem(entry),         // symptom / failure mode / what changed
                extractSolution(entry),        // fix / mitigation / migration steps
                extractSteps(entry),           // structured procedure if present
                extractFeatures(entry),        // type-specific feature mapping
                entry.domain(),
                Map.of("garden", entry.garden(), "type", entry.type())))
            .toList();
    }

    private Map<String, Object> extractFeatures(GardenEntry entry) {
        var features = new LinkedHashMap<String, Object>();

        // Universal features — all entry types carry these
        features.put("domain", FeatureValue.string(entry.domain()));
        features.put("stack", FeatureValue.string(entry.stack()));
        features.put("tags", FeatureValue.stringList(entry.tags()));

        // Type-specific features — each garden type contributes different metadata
        switch (entry.garden()) {
            case "risk" -> {
                features.put("severity", FeatureValue.string(entry.field("severity")));
                features.put("failurePattern", FeatureValue.string(entry.field("failure_pattern")));
            }
            case "evolution" -> {
                features.put("changedIn", FeatureValue.string(entry.field("changed_in")));
                features.put("migrationEffort", FeatureValue.string(entry.field("migration_effort")));
                features.put("breaking", FeatureValue.string(String.valueOf(entry.field("breaking"))));
            }
            case "patterns" -> {
                features.put("stability", FeatureValue.string(entry.field("stability")));
                if (entry.field("suitability") != null) {
                    features.put("suitability", FeatureValue.string(entry.field("suitability")));
                }
            }
            case "discovery" -> {
                // Gotchas: stack version matters most for matching
                if (entry.field("verified_on") != null) {
                    features.put("verifiedOn", FeatureValue.string(entry.field("verified_on")));
                }
            }
        }
        return features;
    }
}
```

**Why per-type features matter:** A risk entry with `severity: critical` and
`failure_pattern: "silent data corruption"` matches differently than a gotcha
with `stack: "Quarkus 3.32"` and `tags: [cdi, bean-resolution]`. The same
retrieval query produces different ranked lists depending on which features
the case context activates — severity-based for incident response, stack-based
for debugging.

#### Feature Design Principles

Features should **discriminate between resolutions that apply to different
situations**, not just describe the document.

**Good features distinguish:** "This runbook is for credential compromise
on Windows domain accounts" vs "This runbook is for credential compromise
on cloud SSO accounts." Both are credential compromise — the `category`
feature alone doesn't help. The `targetPlatform` and `accountType` features
do.

**Bad features are universal:** Every runbook has `hasSteps: true` and
`language: "English"`. These match everything equally and add noise.

Feature weights in `cbr.weights` amplify discriminating features:

```yaml
spec:
  cbr:
    weights:
      severity: 2.0        # severity drives triage decisions
      failurePattern: 3.0   # failure pattern is the strongest discriminator
      stack: 1.0            # stack is useful but secondary
```

#### Staleness Metadata

Garden entries carry staleness metadata that flows into the resolution
pipeline:

- `staleness_threshold` — how many days before the entry should be re-verified
  (730 for gotchas, 3650 for conventions, 1825 for risk entries)
- `verified_on` — version the entry was verified against
- `last_reviewed` — date of last manual review
- `invalidation_triggers` — what changes would make the entry wrong

When `temporalDecayHalfLifeDays` is set on `CbrConfig`, older entries
automatically score lower. Combined with explicit `OUTDATED` feedback
(§Feedback Loop below), stale knowledge degrades gracefully.

#### Adaptive Feature Weights

`CbrFeatureSchema.learningRate` controls how fast feature weights adapt from
retrieval feedback. When analysts consistently select entries that match on
`failurePattern` but ignore entries that only match on `domain`, the system
learns that `failurePattern` is more discriminating.

Set conservatively (`0.01`–`0.05`) for stable domains. Higher values adapt
faster but risk oscillation. Omit to disable (fixed weights only).

### Mixed Retrieval

Set `crossType: true` to retrieve both sources in a single ranked list:

```yaml
spec:
  cbr:
    domain: "soc-incidents"
    crossType: true
    features:
      severity: ".alert.severity"
      category: ".alert.category"
    weights:
      category: 2.0
```

Each result carries `sourceType`:
- `PLAN_TRACE` — a past case execution trace (plan steps, agent names, outcomes)
- `RESOLUTION_GUIDE` — a knowledge base document (prose solution, structured steps)

Workers check `sourceType` to decide how to use the result: follow a historical
plan trace, or follow a documented procedure.

---

## Automated Path

The simplest setup. The agent selects and executes — no human in the loop.

```yaml
dsl: "0.1.0"
namespace: soc
name: phishing-investigation
version: "1.0.0"

spec:
  cbr:
    domain: "soc-incidents"
    crossType: true
    features:
      severity: ".alert.severity"
      category: ".alert.category"

  capabilities:
    - name: investigate
      inputProjection: "{alert: .alert}"

  bindings:
    - name: investigate-alert
      capability: investigate
      on: ".alert != null and .verdict == null"
      producedKeys: [verdict]

workers:
  - name: investigation-agent
    capabilities: [investigate]
    agent:
      model: anthropic
      modelName: claude-sonnet-4-20250514
      systemPrompt: |
        You are a SOC investigator. Use the retrieved experiences
        to guide your investigation approach.
```

CBR retrieval happens automatically at dispatch time. The agent routing
strategy uses `ExperienceSignalProvider` to score agents based on historical
success with similar cases. The agent receives `RetrievedExperience` entries
via `WorkerContext.experiences()`.

---

## Human Path via Work Queues

For cases that need human judgment — the analyst sees ranked candidates in
their work queue and selects the approach.

### Setting Up the Judgment Binding

```yaml
spec:
  bindings:
    # Step 1: Present candidates to the analyst
    - name: select-resolution
      judgment:
        caller:
          human:
            title: "Select investigation approach"
            candidateGroups: [soc-analysts]
            outcomes: [approve, reject, escalate]
        resolutionType: io.casehub.api.model.ResolutionSelection
      on: ".alert != null and .selectedResolution == null"
      producedKeys: [selectedResolution]

    # Step 2: Execute the selected resolution
    - name: investigate-alert
      capability: investigate
      on: ".selectedResolution != null and .verdict == null"
      producedKeys: [verdict]
```

### How It Flows

1. **Case starts** — alert data populates the working context
2. **Judgment binding fires** — `select-resolution` trigger condition matches
3. **Candidates populated** — the engine runs CBR retrieval and writes ranked
   summaries to `_candidates.select-resolution` (caseId, sourceType,
   similarity, problem, confidence, stepCount)
4. **WorkItem created** — the judgment scheduling creates a WorkItem in the
   work inbox for the `soc-analysts` group
5. **Queue routing** — `CaseLabelEvaluator` applies `labelRules` to route the
   case to the appropriate queue view. Analysts see cases in their queue with
   the ranked candidate list attached
6. **Analyst selects** — picks a candidate by `selectedCaseId`, optionally
   provides rationale. Resolution is a `ResolutionSelection(selectedCaseId,
   sourceType, rationale)`
7. **Selection validated** — engine validates `selectedCaseId` against
   `_candidates.select-resolution`. Fabricated or stale IDs are rejected
8. **Capability binding fires** — `investigate-alert` trigger matches on
   `.selectedResolution != null`, dispatches the selected resolution to an agent
9. **Feedback recorded** — selected candidate → `HIGHLY_RELEVANT`, unselected
   above threshold → `PARTIALLY_RELEVANT`

### Queue Routing with Label Rules

Route cases to the right analyst queue based on context:

```yaml
spec:
  labelRules:
    - name: severity-routing
      when: ".alert.severity == \"CRITICAL\""
      actions:
        add: [critical-queue, senior-analysts]

    - name: category-routing
      when: ".alert.category == \"phishing\""
      actions:
        add: [phishing-queue]
```

Labels drive queue view membership via `CaseQueueEntryManager`. An analyst
monitoring the `phishing-queue` view sees phishing cases with their ranked
resolution candidates.

---

## Hybrid Path

The most powerful setup: mixed retrieval from both case history and knowledge
corpus, with optional human oversight on high-stakes cases.

```yaml
spec:
  cbr:
    domain: "soc-incidents"
    crossType: true
    features:
      severity: ".alert.severity"
      category: ".alert.category"

  bindings:
    # High-severity: human selects from candidates
    - name: select-resolution-critical
      judgment:
        caller:
          human:
            title: "Select investigation approach (critical)"
            candidateGroups: [senior-analysts]
        resolutionType: io.casehub.api.model.ResolutionSelection
      on: ".alert != null and .alert.severity == \"CRITICAL\" and .selectedResolution == null"
      producedKeys: [selectedResolution]

    # Low-severity: agent selects automatically
    - name: investigate-auto
      capability: investigate
      on: ".alert != null and .alert.severity != \"CRITICAL\" and .verdict == null"
      producedKeys: [verdict]

    # Execute after human selection
    - name: investigate-selected
      capability: investigate
      inputProjectionOverride: "{alert: .alert, resolution: .selectedResolution}"
      on: ".selectedResolution != null and .verdict == null"
      producedKeys: [verdict]
```

Critical alerts go to the analyst queue. Routine alerts dispatch automatically.
Both paths use the same CBR retrieval, the same ranking, and the same feedback
loop.

---

## The Feedback Loop

Feedback has two dimensions that compound over time:

### Retrieval Relevance — "Were the right things retrieved?"

This measures whether the similarity matching produced candidates that
actually fit the situation. Not whether the resolution worked, but whether
it was retrieved for the right reasons.

| Signal | Source | What it means |
|--------|--------|--------------|
| `RELEVANT` | Layer 1 — worker success | The retrieved experience matched the situation |
| `NOT_RELEVANT` | Layer 1 — worker failure/decline | The retrieval matched on surface features but the experience didn't apply |
| `HIGHLY_RELEVANT` | Layer 3 — human selected | Strongest signal — an expert confirmed this is the right match |
| `PARTIALLY_RELEVANT` | Layer 3 — human didn't select, but similarity was high | Close match, but something better existed |
| `OUTDATED` | Explicit marking or staleness detection | Was relevant once, no longer accurate |

**Why this matters for documents:** A runbook about a retired system keeps
getting retrieved because its features overlap with active systems. Analysts
skip it every time. The accumulating `NOT_RELEVANT` and absent
`HIGHLY_RELEVANT` signals push it down for those query patterns, without
anyone needing to manually remove it.

### Outcome Quality — "Did the resolution work?"

This measures whether the selected resolution was effective:

| Signal | Source | What it means |
|--------|--------|--------------|
| Outcome confidence | Layer 2 — case completion | EMA of success/failure across all uses of this resolution |
| Outcome weighting | Retrieval ranking | Higher confidence → higher retrieval score (`score × (1 - α + α × confidence)`) |

A runbook that gets selected but whose steps consistently fail sees its
confidence degrade. It still appears in results (it matched the query), but
ranks lower than alternatives with better track records.

### Staleness and Decay

Knowledge ages. Two mechanisms handle this:

**Temporal decay** — `temporalDecayHalfLifeDays` on `CbrConfig` reduces
retrieval scores for older entries. A 90-day half-life means an entry from
6 months ago scores at ~25% of its original similarity. Appropriate for
fast-moving domains (SOC threat landscape, evolving compliance rules).

```yaml
spec:
  cbr:
    temporalDecayHalfLifeDays: 90
```

**Explicit staleness** — `OUTDATED` feedback marks a specific entry as no
longer accurate. Unlike `NOT_RELEVANT` (which says "wrong match for this
query"), `OUTDATED` says "this was correct once but the underlying
procedure/system/regulation changed." Outdated entries are penalised
across all queries, not just the one that triggered the feedback.

### How the Signals Compound

Consider a runbook about credential reset procedures:

1. **Year 1:** Written and ingested. Retrieved for credential-compromise cases.
   Analysts select it frequently → `HIGHLY_RELEVANT` accumulates. Workers
   follow the steps successfully → outcome confidence rises. Retrieval score
   is high.

2. **Year 2:** IT changes the credential management system. The runbook steps
   no longer work. Workers start failing → outcome confidence drops via EMA.
   Analysts stop selecting it → `HIGHLY_RELEVANT` signals stop, `NOT_RELEVANT`
   from automated failures accumulate. Temporal decay also reduces its score.

3. **Someone marks it `OUTDATED`** → retrieval penalty across all queries.
   The document drops out of top-K results. A new runbook with the updated
   procedures takes its place.

No one deleted the old runbook. No one manually re-ranked anything. The
feedback loop handled it.

---

## Tuning

### Feature Weights

Features with higher weights have more influence on similarity matching:

```yaml
spec:
  cbr:
    features:
      severity: ".alert.severity"
      category: ".alert.category"
      region: ".alert.region"
    weights:
      category: 3.0    # category matters most for resolution selection
      severity: 1.5    # severity influences approach
                        # region: default 1.0
```

Start with 3-5 features. Weight the ones that discriminate between different
resolution approaches. Equal weights waste discriminative power.

### Outcome Weighting

Enabled by default. Controls how much outcome confidence affects ranking:

```properties
casehub.cbr.outcome-weighting.enabled=true
casehub.cbr.outcome-weighting.influence=0.3    # α parameter
```

At `α = 0.0`: pure similarity (confidence ignored). At `α = 1.0`: fully
confidence-driven. Default `0.3` promotes proven resolutions without burying
untested ones.

**Cold start:** newly ingested documents have null confidence, which defaults
to 1.0 — no penalty. Documents are ranked purely by similarity until they
accumulate outcome feedback.

### Selection Feedback Threshold

Controls which unselected candidates receive `PARTIALLY_RELEVANT` feedback:

```properties
casehub.cbr.selection-feedback.threshold=0.5
```

Candidates with similarity ≥ threshold that weren't selected get
`PARTIALLY_RELEVANT`. Below threshold: no signal. Higher thresholds produce
fewer but more precise feedback signals.

### Temporal Decay

Set based on domain knowledge velocity:

| Domain | Suggested half-life | Rationale |
|--------|-------------------|-----------|
| SOC / threat intel | 60-90 days | Threat landscape evolves rapidly |
| AML / financial crime | 180-365 days | Regulatory changes are slower |
| Clinical protocols | 365+ days | Medical procedures change infrequently |
| IT operations | 90-180 days | Infrastructure changes at moderate pace |
| Stable reference | omit | Historical knowledge stays relevant indefinitely |

### Minimum Similarity

Set `minSimilarity` to filter noise:

```yaml
spec:
  cbr:
    minSimilarity: 0.3    # below this, retrieved cases are usually irrelevant
```

Start at `0.3`. Raise if analysts report too many irrelevant candidates.
Lower if the domain is diverse and even weak matches are useful.

---

## Dependencies

| Capability | Required dependency |
|-----------|-------------------|
| CBR retrieval (core) | `casehub-neocortex-memory` (classpath) |
| Retrieval feedback (Layers 1+3) | `casehub-neocortex-memory-cbr-tracking` (classpath) |
| Outcome weighting | `casehub-neocortex-memory` (config property, default on) |
| Document ingestion | Consumer implements `CorpusSourceAdapter` |
| Work queues | `casehub-engine-queue` + `casehub-platform-view` (classpath) |
| Judgment scheduling | `casehub-engine-work-cloudevent` or `casehub-work-engine-adapter` (classpath) |

All feedback components are transparent no-ops when their dependencies are
absent — the pipeline works at every level of dependency.
