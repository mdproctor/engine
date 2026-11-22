# Brief Notes — Cognition, Reasoning & Knowledge (neocortex)

> Maps to MULTIPLE capability areas in the brief. Do not group under one heading.

## Capability Areas

### 1. On-Device Inference

**What it does:** Standalone ONNX inference layer for JVM. Runs NLI, classification, regression, SPLADE sparse embeddings, cross-encoder reranking, and tensor classification locally — no external API calls.

**Differentiation:**
- Fills the gap LangChain4j leaves: LangChain4j handles dense embeddings but has no NLI, classification, regression, or SPLADE
- Zero domain dependencies — shared with Hortora (non-CaseHub project), ArchUnit-enforced
- Sealed `InferenceInput` hierarchy (Text + Tensor variants) with typed task adapters — callers never touch raw tensors
- BGE-M3 multi-modal embedder produces dense + sparse + ColBERT from a single ONNX model run
- Native image compatibility validated (C2 gate passed)

**Extent:** 7 modules (api, runtime, tasks, splade, bge-m3, inmem, quarkus). 5 typed task adapters. Full ONNX Runtime JNI + HuggingFace Tokenizers JNI integration.

**Consistency benefit:** CDI-wired via inference-quarkus. `@InferenceModel` qualifier. Dev Services. `@QuarkusTest` support. In-memory stubs for test isolation.

### 2. Corpus Knowledge & Retrieval (RAG)

**What it does:** Three-leg hybrid search (dense + sparse + BM25) over tenant-isolated Qdrant vector stores. Pre-ingestion dedup gate, corrective RAG quality-gating, cross-encoder reranking, query expansion (HyDE, step-back, multi-query), Matryoshka dimension reduction, retrieval tracking with feedback.

**Differentiation:**
- True 3-leg hybrid search — most RAG implementations only do dense or dense+sparse. BM25 as a third leg catches keyword matches that embedding similarity misses
- Configurable fusion strategies (RRF, DBSF, Convex Combination) with per-query weight multipliers
- Pre-ingestion dedup gate (cosine similarity check before indexing) prevents corpus bloat
- Corrective RAG quality-gating — grades chunks via cross-encoder before returning, config-gated
- Multi-corpus retrieval with per-corpus error isolation via `CaseContextRetriever`
- RetrievalAnalyzer: document stats, query clusters (MinHash LSH), correlation graph, document impact — pure computation analytics over tracking data
- ColBERT relevance evaluation with automatic calibration from sample score distributions

**Extent:** 11 modules (api, core, tika, crossencoder, expansion, scoring, query-augmentation, spring, cache, tracking, main). Full LangChain4j pipeline with tenancy isolation. Apache Tika for binary document parsing.

**Consistency benefit:** Tenancy via `TenantGuard` + `CurrentPrincipal`. CDI decorator chain for feature gating. Shared `FusionStrategy` enum with CBR. Config-driven via standard `casehub.rag.*` properties.

### 3. Case-Based Reasoning (CBR)

**What it does:** Typed feature-vector similarity search over prior cases. Weighted composite scoring with DTW for time series, edit distance for sequences. Trust-weighted retrieval, temporal decay, hierarchical scoping, outcome feedback loops, plan adaptation, ensemble analysis.

**Differentiation:**
- 7 typed feature value types (StringVal through StructListVal) with 9 field types (Categorical through DiscreteSequence) — not string-only matching
- Dynamic Time Warping (DTW) similarity with O(n) LbKeogh lower-bound pruning — handles time series of different lengths and alignments
- Edit distance similarity for discrete sequences (plan steps, action chains)
- Cross-type retrieval: `AllInDomain` scope fans out across all Qdrant collections matching domain prefix
- Trust-weighted retrieval modulates scores by source agent trust + trajectory via `AgentTrustProvider` SPI
- Plan adaptation pipeline: `CbrPlanAdapter` transforms retrieved plans for new contexts, `CbrPlanEnsembleAnalyzer` synthesizes consensus from multiple adapted plans
- Supersession model: cases can be superseded (with reason) and reinstated — audit trail maintained
- Outcome feedback with EMA confidence adjustment — cases that lead to bad outcomes decay automatically
- Domain-specific CBR guides exist for AML, Clinical, DevTown, IoT, Life, Engine — each with tailored feature schemas

**Extent:** 7 dedicated CBR modules + shared memory-api. CDI decorator chain: trend enrichment (@Priority 90) → scope decay (85) → temporal decay (80) → outcome weighting (65) → trust weighting (60) → tracking (50) → erasure notification (45). JPA, in-memory, Qdrant backends. SQLite-backed retrieval + adaptation + ensemble tracking. 164-test contract test suite.

**Consistency benefit:** Same CDI decorator pattern as RAG. Shared `FusionStrategy`. Tenant-scoped. Ledger-compatible via `AgentTrustProvider` bridging to ledger trust scores. Config-driven via `casehub.cbr.*` properties.

### 4. Agent Memory

**What it does:** Queryable, permission-aware, persistent agent memory with confidence-based retention. Multiple ordering modes (chronological, relevance, salience). GDPR-compliant erasure including cross-tenant.

**Differentiation:**
- Salience ordering: recency × confidence scoring — not just "most recent" or "most similar"
- Fire-and-forget emission via `MemoryEmitter` — error isolation, SecurityException-only propagation
- 7 backends: in-memory, JPA/PostgreSQL (FTS), SQLite (FTS5), Mem0 (vector), Graphiti (temporal knowledge graph), Spring JPA, Qdrant
- Cross-tenant entity erasure — GDPR Article 17 compliance across tenant boundaries
- `Subject` bridge for cross-store entity references without module coupling
- Confidence-based retention scheduling — low-confidence memories purge automatically

**Extent:** 17 modules spanning CDI + Spring frameworks. Contract test suites for all store implementations.

**Consistency benefit:** CDI decorator chain. Tenant-scoped. GDPR erasure events. Config-driven retention.

### 5. Knowledge Graph (MindMap / Thing Model)

**What it does:** Entity-centric knowledge graph with dynamic type system, confidence-aware nodes, PAD emotional dimensions, temporal validity, and trait-based typed access via JDK Proxy.

**Differentiation:**
- `Thing` interface as universal entity base — identity, properties, traits, dynamic type checking via `is()`/`as()`
- `as()` creates JDK Proxy mapping interface method names to property lookups — zero-boilerplate typed access
- Types are dynamic strings stored in graph nodes, not a fixed enum — LLMs can discover new entity types at runtime
- `TypeRegistry` stores type hierarchy as graph data — type operations ARE graph traversal
- `MindMapNode extends Thing` with cognitive fields: confidence (with origin + decay), PAD emotions, temporal validity, provenance
- Consolidation pipeline: attention tracking → merge detection → community summaries → curiosity refresh
- Derived edge rules: declarative YAML rules for automatic relationship inference
- Schema validation advisory — documents expectations without rejecting novel properties

**Extent:** 8 modules (api, core, inmem, intelligence, spring, sqlite, testing, main). 72-test contract test suite. Trait system with built-in Personable, Projectlike, Organisational, Eventlike interfaces.

**Consistency benefit:** CDI decorators for confidence decay, vocabulary normalization, derived edges. Tenant-scoped. `Subject` bridge to Agent Memory for cross-store references.

### 6. Cognitive Architecture (NEW — partially delivered, active development)

**What it does:** Goal cognition, OCC emotion appraisal, progressive attention, personality-prior calibration, social comparison, cross-domain affect correlation, cognitive profiles with multi-agent perspectival views.

**Differentiation:**
- Goal cognition pipeline: Recognition → Affect → Prioritization → Resolution → Decay — 5-phase lifecycle
- OCC emotion framework: agent-based emotions (Pride, Shame, Admiration, Reproach) + compound emotions (Gratitude, Anger, Remorse, Gratification)
- Personality-prior calibration from JPAF (Jungian Personality Assessment Framework) — appraisal weights derive from personality
- Progressive cognitive attention: per-principal threshold accumulator with urgency spike, priority shift, and decay detection signals
- `CognitiveProfile` — entity resolution across MindMap + Memory stores with perspectival views (agent overlays)
- `SocialComparison` — PAD distance matrix, trajectory alignment, divergence metrics between agents' perspectives
- `DomainActivation` — cross-domain DTW correlation of affect signals with optional mood/experience context

**Extent:** 7 cognitive modules (api, index, observability × 4, testing). `cognitive-index` alone has 60+ classes. Active development: emergent behavioral synthesis (epic #406), multi-agent Inside Out model (#392), adaptive cognitive brief (#391).

**Vision from open issues:**
- Emergent behavioral synthesis — crystallized behaviors from memory + disposition (#406, #397)
- Cause-effect decision graph engine (#408)
- Multi-agent "Inside Out" model with sub-LLMs per cognitive domain (#392)
- Memory seeding infrastructure for fabricated backstory (#398)
- Context-budget-aware prompt rendering (#393)
- Runtime gut feeling — affect similarity probe (#409)

**Consistency benefit:** `cognitive-api` types (`Confidence`, `TemporalMark`, `CognitiveEmotion`) shared across all cognitive stores. YAML-driven per-agent config via `CognitiveDefaultsRegistry` with file-watch hot-reload.

## Cross-Capability Themes

- **Homogeneous consistency**: All 6 capability areas share CDI decorator chains, tenant scoping, config-driven feature gating, `@DefaultBean` + `@Alternative` test isolation, and contract test suites
- **Spring parity**: Memory and MindMap both have Spring Boot auto-configuration modules — platform isn't CDI-only
- **Shared fusion**: RAG and CBR share `FusionStrategy` enum and `ScoreFusion` algorithms — same mathematical foundation for both retrieval models
- **GDPR throughout**: Memory, CBR, and MindMap all support entity erasure with CDI event notification
- **Observability**: Cognitive observability modules (core, spring, sqlite, testing) provide cross-store mutation tracking

## Guide Audit Findings

**Module count:** Contributor-index claims 36 modules. Actual count is 61 (root pom minus root = 61 child modules). Significant undercount.

**Missing from guides:**
1. Goal cognition subsystem (5 phases, goal traits, goal vocabulary) — added since Sept 2026
2. OCC emotion framework (agent-based + compound emotions, ALMA PAD table) — added Sept 2026
3. Progressive cognitive attention model (accumulator, signals, briefing) — added Sept 2026
4. Personality-prior calibration (JPAF-derived appraisal weights) — added Sept 2026
5. `memory-core` module (framework-neutral runtime) — refactored June 2026
6. `sqlite-support` shared module (SqliteDataSourceFactory) — refactored June 2026
7. Cognitive observability modules (6 modules) — not documented
8. `rag-query-augmentation`, `rag-scoring`, `rag-cache` modules — not documented
9. Spring Boot modules (memory-spring, memory-spring-jpa, memory-cbr-spring-jpa, mindmap-spring, cognitive-observability-spring, rag-spring) — partially documented

**Stale claims:**
- Module count (36 vs 61)
- Missing Spring support narrative
- No mention of framework-neutral extraction pattern (memory-core, cognitive-observability-core)

**Accurate claims:**
- Inference subsystem documentation is thorough and current
- RAG pipeline documentation is thorough and current
- CBR documentation is thorough and current
- Agent Memory documentation is thorough and current
- MindMap/Thing documentation is thorough and current
- Configuration tables are accurate