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

### Corpus Metadata — Feature Schema Design

Feature metadata is the single most important decision for retrieval quality.
Without well-designed features, the system falls back to pure semantic
similarity on the problem text — which works for broad matching but can't
distinguish a phishing runbook from a malware runbook when both mention
"suspicious email."

**`CbrFeatureSchema`** (neocortex `memory-api`) declares what metadata a
corpus carries. Each schema has a `caseType` (matching the `cbr.caseType`
in YAML), a list of typed `FeatureField` declarations, and an optional
`learningRate` controlling how fast feature weights adapt from feedback.

#### Feature Field Types

| Type | Factory | Use for | Similarity |
|------|---------|---------|------------|
| `categorical` | `FeatureField.categorical("name")` | Tags, categories, enum values | Exact match (or vocabulary-grounded subsumption) |
| `numeric` | `FeatureField.numeric("name", min, max)` | Severity scores, confidence, counts | Range-normalised distance |
| `text` | `FeatureField.text("name")` | Short descriptions, titles | BM25 term matching |
| `semanticText` | `FeatureField.semanticText("name")` | Free-form descriptions, summaries | Embedding cosine similarity |
| `categoricalList` | `FeatureField.categoricalList("name")` | Multiple labels, IOC types, MITRE tactics | Jaccard set similarity |
| `numericList` | `FeatureField.numericList("name", min, max)` | Score arrays, multi-valued metrics | Element-wise distance |
| `nestedObject` | `FeatureField.nestedObject("name", subfields...)` | Structured sub-features | Recursive field-level matching |
| `objectList` | `FeatureField.objectList("name", subfields...)` | Lists of structured items | Best-match pairing |
| `timeSeries` | `FeatureField.timeSeries("name", tsField, valueFields...)` | Temporal patterns, event sequences | DTW (Dynamic Time Warping) |
| `discreteSequence` | `FeatureField.discreteSequence("name")` | Step sequences, action chains | Sequence alignment |

Each field type accepts an optional `SimilaritySpec` to tune comparison
behaviour (e.g., custom distance functions, weighting within nested objects).

#### Designing Features for a Knowledge Corpus

The goal: features should **discriminate between resolutions that apply to
different situations**, not just describe the document.

**Good features distinguish:** "This runbook is for credential compromise
on Windows domain accounts" vs "This runbook is for credential compromise
on cloud SSO accounts." Both are credential compromise — the category feature
alone doesn't help. Add `platform: categorical` and `accountType: categorical`.

**Bad features are universal:** Every runbook has `hasSteps: true` and
`language: "English"`. These features match everything equally and add noise
without discriminative value.

```java
// SOC domain — features that discriminate between investigation approaches
var schema = CbrFeatureSchema.of("soc-investigation",
    FeatureField.categorical("category"),           // phishing, malware, insider-threat, etc.
    FeatureField.categorical("attackVector"),        // email, web, usb, lateral-movement
    FeatureField.categorical("targetPlatform"),      // windows-ad, cloud-sso, linux-server
    FeatureField.numeric("severityScore", 0, 10),    // normalised severity
    FeatureField.categoricalList("mitreTactics"),    // T1566, T1078, etc.
    FeatureField.semanticText("problemSummary"));    // free-form — catches novel situations
```

#### How Feature Types Drive Retrieval

When a new case arrives with `category: "phishing"` and `severityScore: 8.5`:

1. **Categorical match** — `category` filters to phishing-related entries
   (exact match or vocabulary-grounded subsumption if eidos is active)
2. **Numeric distance** — `severityScore: 8.5` is closer to entries with
   severity 9 than severity 3, normalised within the declared `[0, 10]` range
3. **List similarity** — `mitreTactics: [T1566, T1534]` uses Jaccard against
   each entry's tactic list
4. **Semantic fallback** — `problemSummary` catches novel phishing variants
   that don't match any categorical feature exactly
5. **Feature weights** — `cbr.weights.category: 3.0` amplifies category's
   influence; unweighted features default to `1.0`

The combined score ranks entries that match on multiple discriminating
features above entries that match only on semantic text.

#### Feature Extraction in the Adapter

The `CorpusSourceAdapter` maps from your knowledge base's metadata format
to `FeatureValue` instances:

```java
private Map<String, Object> extractFeatures(Document doc) {
    return Map.of(
        "category", FeatureValue.string(doc.getCategory()),
        "attackVector", FeatureValue.string(doc.getAttackVector()),
        "severityScore", FeatureValue.number(doc.getSeverityScore()),
        "mitreTactics", FeatureValue.stringList(doc.getMitreTactics()),
        "problemSummary", FeatureValue.string(doc.getSummary()));
}
```

`ResolutionIngestionService.mapFeatures()` converts `Map<String, Object>` to
`Map<String, FeatureValue>` automatically — plain strings, numbers, and lists
are converted via `FeatureValue.of()`.

#### Adaptive Feature Weights

`CbrFeatureSchema.learningRate` controls how fast feature weights adapt from
retrieval feedback. When an analyst consistently selects entries that match
on `attackVector` but ignores entries that only match on `category`, the
system learns that `attackVector` is more discriminating for this domain.

Set `learningRate` conservatively (`0.01`–`0.05`) for stable domains. Higher
values (`0.1`+) adapt faster but risk oscillation in domains with inconsistent
feedback patterns. Omit to disable adaptive weighting entirely (fixed weights
from `cbr.weights` only).

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
