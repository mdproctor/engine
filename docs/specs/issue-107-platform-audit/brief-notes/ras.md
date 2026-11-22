# Brief Notes — Situation Awareness & Reactive Detection

> Source repo: ras (9 modules, 122 main Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Pluggable Detection Engine
Event-driven situational awareness via CloudEvent observation. Routes events to matching situation definitions, evaluates pluggable detection strategies (ganglia), accumulates evidence across time windows, and triggers case creation or notifications when thresholds cross. Definition-driven routing — ganglia evaluate, the engine decides.
- **Differentiation:** Five ganglion types (JavaSwitch, NaiveBayes, ExpressionRules, SituationWatcher, Drools CEP) cover the full detection spectrum from simple stateless pattern matching through incremental Bayesian classification to full CEP stream-mode processing. No competitor offers this range under a single SPI. The biological metaphor (ganglion = neural cluster) maps directly to the composition model.
- **Extent:** 9 modules (api, runtime, persistence-memory, persistence-jpa, ras-drools, drools-reliability, ras-llm placeholder, testing). 7 ChainMode composition strategies (And, Or, Threshold, Sequence, Count, Streak, Rate). 6 TriggerDecision outcomes. YAML-based situation definitions with expression support (JQ, MVEL), evidence templates, dynamic confidence expressions, and situation templates with parameter substitution. Dual-layer OCC for clustered deployments. Flyway V1-V10.

### 2. Composable Signal Architecture
Seven sealed ChainMode variants compose simple ganglion signals into complex detection patterns. Each chainmode is itself composable via nested situation definitions. Meta-situations (#41) add hierarchical observation — a parent situation can watch child situations' lifecycle events, enabling cascading detection patterns.
- **Differentiation:** Sealed algebraic composition (And/Or/Threshold/Sequence/Count/Streak/Rate) is type-safe and exhaustive — unlike string-based or config-driven composition in competing CEP systems. The SituationWatcherGanglion adds a dimension no CEP system offers: situation-watching-situations with cycle detection at registration time.
- **Extent:** Complete sealed hierarchy, YAML-expressible, with compile-time exhaustiveness checks on pattern matching. Cycle detection via DFS at registration time prevents circular observation chains. Deadline-based triggering for time-bounded situations where non-detection is itself a signal.

### 3. Feedback-Driven Learning Loop
Closed-loop sense-decide-act-learn cycle. Case outcomes feed back into detection via suppression, threshold drift, prior recalibration, and recall metrics. Two modes: advisory (suppression + metrics only) and tuning (automatic parameter adjustment).
- **Differentiation:** Three-layer feedback architecture (ingestion → analysis → application) with both automatic and manual tuning. Per-ganglion quality metrics (precision, noise rate, recall). Drift classification model (DriftDirection: OVER_SENSITIVE, UNDER_SENSITIVE, BOTH_DRIFTING, STABLE, INSUFFICIENT_DATA) with guard against counter-productive auto-tuning. External missed-detection API (POST /api/ras/feedback/missed) with cross-reference against trigger history. No CEP system integrates outcome-driven parameter tuning this deeply.
- **Extent:** OutcomeLedger with JPA persistence. FeedbackTuningStrategy SPI with pluggable strategies. NaiveBayes prior recalibration via outcome-to-outcome mapping. Threshold adjustment for Threshold and Rate chain modes. Per-ganglion recall via external missed detection reports with ganglion attribution. Micrometer metrics for all feedback dimensions.

### 4. Clustered Conflict Resolution
Two-phase processing (detect-once, apply-with-retry) with dual-layer OCC (application-level storeVersion + JPA @Version). Duplicate trigger prevention via CAS-based claim mechanism.
- **Differentiation:** Purpose-built for clustered deployment without distributed locks. Bifurcated claim path (new=save-before-claim, existing=claim-before-save) with deferred cleanup via SituationExpiryJob. Most event processing frameworks either require single-writer or use distributed locks.
- **Extent:** Configurable max retries (default 3). Guard period for triggered entities. Orphaned resource cleanup SPI for derived state (ganglion state, Drools sessions).

### 5. Situation Replay & Validation
Deterministic replay of historical CloudEvent streams against situation definitions. Uses the real detection pipeline without side effects.
- **Differentiation:** Not a separate simulation mode — uses the production SituationEvaluator.evaluate() with deterministic time handling. Feedback components optionally included for full-fidelity replay. Error handling modes (STRICT/LENIENT) for different validation scenarios.
- **Extent:** Builder API, aggregate stats, per-situation-instance state queries. Event reorder buffer flushing. Dead-letter tracking for unmatched events.

## Cross-Repo Patterns

- **Platform dependency:** Consumes CloudEvents produced by platform stream modules (Kafka, AMQP, webhook, Camel). Uses platform ExpressionEvaluator SPI for JQ/MVEL expressions. Clean event-only boundary — RAS never imports transport libraries.
- **Engine integration:** Triggers case creation via engine-api CaseHub SPI. CaseOutcomeObserver SPI feeds case outcomes back into the feedback loop. Meta-situations can compose engine-level case decisions.
- **IoT integration:** casehub-iot uses RasTriggerPolicy and SituationDefinitionRegistry for IoT-specific event detection. Shared YAML parsing logic extracted for IoT situation provider (#55).
- **DesiredState dependency:** SituationSource and ActiveSituation types were migrated from desiredstate-api to ras-api (#22), establishing RAS as the canonical situational awareness layer.

## Vision

- **YAML situation definitions from app.yaml** (#67, OPEN) — load situations from the standard application descriptor
- **Migrate Naive Bayes to platform-algorithms** (#65, OPEN) — promote classifier to shared platform module
- **Expand built-in template library** (#56, OPEN) — And/Or/Sequence chain mode templates
- **Clustered DroolsSessionStore** (#30, OPEN) — shared session state across JVMs
- **Journal-based DroolsSessionStore reliability** (#29, OPEN) — write-ahead log for Drools session persistence
- **RAS/Qhorus adapter** (#66, CLOSED) — channel situation awareness from commitment lifecycle events