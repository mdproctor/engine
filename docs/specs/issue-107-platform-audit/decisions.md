## D1: Index structure

**Choice:** Single flat YAML index (docs-inventory.yaml) in parent/docs/audit/
**Alternatives:**
- Per-repo index files + master catalog — modular but unnecessary overhead for a one-off build
- Topic-first index — capability views derivable from flat index via filtering
**Rationale:** One file, one load, standard YAML filtering. Flat structure has no hierarchy assumptions to break when docs move. Serves repo-scoped queries (guide audit), capability-scoped queries (brief writing), and type-scoped queries (find all specs, find all blogs).
**Trade-offs:** No per-repo modularity for updates, but acceptable for a one-off build that's rebuilt rather than incrementally maintained.
**Sources:** parent/docs/audit/ (existing audit directory), parent/docs/INDEX.md (existing index infrastructure)
**Exploration:** quick
**Status:** captured

## D2: Entry schema — two-tier (structural + semantic)

**Choice:** Python generates structural skeleton (path, repo, source, headings[], last_modified). LLM augments with semantic fields (type, audience, summary, topics[], extraction_pct) over iterative passes.
**Alternatives:**
- Python does everything including summarisation — Python can't reliably summarise or classify content
- All LLM from scratch — wastes LLM passes on mechanical filesystem/git work that Python handles in seconds
**Rationale:** Python is fast and reliable for filesystem traversal, heading extraction (regex on `^#+`), and git log dates. Content interpretation — what type is this, who's the audience, what does it cover — requires LLM judgment. Two-tier split gives each tool its strength. extraction_pct tracks how much of each file the LLM has processed across passes.
**Trade-offs:** Two-phase build means the index starts incomplete (structural only) and grows richer over LLM passes. But that's the explicit design intent — build up iteratively.
**Depends on:** D1 (flat YAML structure hosts both tiers)
**Sources:** parent/docs/consumer-index.md (audience classification example), parent/docs/capabilities.md (topic classification example)
**Exploration:** quick
**Status:** captured

## D3: Index scope — all documentary sources

**Choice:** Index covers ALL .md files across product repos, blog sites, specs, and research — not just guides
**Alternatives:**
- Guides only — misses specs, research, and blogs that are source material for the brief
- Guides + specs only — misses blog entries which contain design rationale, architectural narratives, and vision
**Rationale:** The brief needs to draw on everything: guides for current capabilities, specs for design rationale, research for forward-looking positioning, blogs for narrative and vision. The structural index is cheap to build across all sources. LLM passes prioritise what to read first based on the heading structure.
**Trade-offs:** Larger index (~500+ entries) but still small. The LLM's extraction_pct tracking means it processes high-value files first and builds up coverage over passes.
**Sources:** parent/docs/specs/ (~35 specs), parent/docs/research/ (2 docs), casehubio.github.io/_articles/ (10 posts), mdproctor.github.io/_articles/ (CaseHub-related posts)
**Exploration:** quick
**Status:** captured

## D4: Brief organises by capability area, not repo name

**Choice:** Capability-oriented taxonomy with descriptive names; repo-internal product names never appear in the brief
**Alternatives:**
- Repo-organised — groups by code boundary, not business capability. Readers don't care about "qhorus", they care about "Agentic Communications Mesh"
- Hybrid (capability groups with repo footnotes) — leaks internal naming into a market-facing document
**Rationale:** The brief sells what the platform DOES, not how the code is structured. One repo may map to multiple capability areas (e.g. neocortex → Cognition + Case-Based Reasoning + Corpus Knowledge RAG). The taxonomy uses descriptive names: "Fleet Manager" not "Claudony", "Agentic Communications Mesh" not "Qhorus". A capability-to-repo mapping exists as an internal reference but doesn't surface in the brief itself.
**Trade-offs:** Requires building and maintaining a name mapping table. But this mapping is itself a design artifact that clarifies the platform's value proposition.
**Sources:** parent/docs/capabilities.md (existing capability-to-repo mapping, but uses repo names), issue #107 body (positioning guidance)
**Exploration:** quick
**Status:** captured

## D5: Brief is a layered document with drill-downs and SVGs

**Choice:** Multi-layer document: tagline/story → capability areas → specific capabilities → use cases, with SVG diagrams throughout
**Alternatives:**
- Flat executive summary — loses the drill-down that lets different audiences read at different depths
- Separate documents per capability — loses the holistic story of what the whole platform delivers
**Rationale:** Top layer: what is CaseHub, what problems it solves, market positioning, tagline. Middle layer: capability areas (Orchestration, Cognition, Communications, Trust, etc.) with architecture SVGs. Bottom layer: specific capabilities with use case flows. E2E use cases weave across capability areas. Forward-looking content from issues/specs shows vision, not just delivered state. SVGs included aggressively — architecture, components, use cases, step flows — can always be pruned later.
**Trade-offs:** Multi-layer document is harder to write (cross-references, consistency across layers) and needs holistic thinking, not per-repo-scoped work. But that's the whole point — the brief must tell one coherent platform story.
**Depends on:** D4 (capability taxonomy structures the layers)
**Sources:** issue #107 body (brief positioning), parent/docs/PLATFORM.md (existing top-level doc), parent/docs/APPLICATIONS.md (use case domains)
**Exploration:** quick
**Status:** captured

## D6: Platform positioning — enterprise Java foundation with YAML accessibility

**Choice:** Position CaseHub as an enterprise-class Java platform for automation, coordination, and collaboration — with YAML as the accessibility breakthrough that opens it to domain experts
**Alternatives:**
- Lead with YAML/AI — "agentic AI for everyone" without the enterprise anchor. Risks being grouped with lightweight Python agent frameworks
- Lead with enterprise Java — emphasises depth but undersells accessibility. Risks sounding like legacy BPMN middleware
**Rationale:** The platform's substance is enterprise Java (Quarkus, CDI, JPA, transactional integrity, type safety). Its differentiator is that this power is accessible through YAML — domain experts can build multi-agent, accountability-native applications without being Java engineers. Neither the platform depth nor the YAML accessibility tells the full story alone. Three pillars organise the capability areas: Automation (case orchestration, workers, desired-state, planning), Coordination (agent mesh, speech acts, trust routing, oversight), Collaboration (human tasks, channels, notifications, agent-human handoff).
**Trade-offs:** Dual messaging (enterprise + accessible) is harder to tagline than a single-axis pitch. But the combination IS the differentiator.
**Sources:** issue #107 body (positioning guidance), parent/docs/PLATFORM.md, Quarkus 3.32.2 stack
**Exploration:** quick
**Status:** captured

## D7: E2E lifecycle narrative as the brief's spine

**Choice:** Structure the brief around a complete lifecycle arc: Declaration (YAML manifests) → Intelligence (LLMs in agentic patterns) → Control (planning, oversight, trust, accountability) → Convergence (desired-state reconciliation) → Operations (situation awareness, remediation) — and the feedback loop from Ops back to Declaration.
**Alternatives:**
- Capability catalog — lists what exists but doesn't tell a story. Reader can't see the whole.
- Three-pillar organisation only (automation/coordination/collaboration) — organises capabilities but doesn't show how they compose end-to-end
**Rationale:** The platform's unique value is that it covers the full lifecycle. No competitor does 1-through-5. LangChain covers step 2. Camunda covers step 3. Kubernetes covers step 4. CaseHub covers all five as one coherent platform, declarable in YAML, executed on enterprise Java, with full accountability. The brief must make this end-to-end story its central narrative. The three pillars (D6) organise capability areas within this arc; the lifecycle arc is how they compose.
**Trade-offs:** Lifecycle framing requires the brief to show cross-capability composition at every layer, not just in the use-case section. Harder to write but essential to the positioning.
**Depends on:** D6 (enterprise positioning provides the "how"), D5 (layered structure hosts the arc)
**Sources:** engine case lifecycle, blocks agentic patterns, desiredstate reconciliation loop, ras situation awareness, ops operational monitoring
**Exploration:** quick
**Status:** captured

## D8: Per-area differentiation, extent, and homogeneous consistency

**Choice:** Each capability area in the brief must explain three things: (1) its differentiation vs alternatives, (2) its extent (how deep/broad the implementation goes), and (3) how it benefits from the platform's homogeneous consistency. The platform's breadth of consistent, composable capabilities is itself a top-level differentiator.
**Alternatives:**
- Feature lists per area — shows what exists but not why it matters or how deep it goes
- Differentiation only, no extent — misses that CaseHub's capabilities aren't thin wrappers; they're deep implementations
**Rationale:** Competitors may match individual capabilities. Nobody matches the breadth with homogeneous consistency — same CDI model, event system, type safety, tenancy, audit trail across every capability. This consistency means capabilities compose without integration glue. The brief must make this visible per area AND as a platform-level theme. Additionally, the platform is written by LLMs for LLMs — designed as the most productive and powerful AI Harness for Digital Transformation through Automation, Coordination and Collaboration (AIH-DT-ACC). Every API, DSL, and SPI is designed for LLM operability.
**Trade-offs:** Per-area differentiation analysis requires competitive research for each capability area. But without it, the brief is a feature catalog, not a positioning document.
**Depends on:** D6 (enterprise positioning), D7 (lifecycle spine)
**Sources:** Platform CDI conventions, shared event model, tenant-scoped repositories, audit trail integration across repos
**Exploration:** quick
**Status:** captured

## D9: Brief writing starts after Wave 1 foundation audit (renumbered)

**Choice:** Begin brief drafting once the 10 foundation repos (platform, worker, ledger, connectors, work, qhorus, eidos, neocortex, engine, iot) are audited. Refine as Waves 2-3 complete.
**Alternatives:**
- All waves first — slower, brief delayed until all 29 repos audited, but avoids rewriting
- Brief in parallel from day one — fastest first draft but highest risk of rewriting from stale guides
**Rationale:** Foundation repos define the core capabilities that the brief's top and middle layers describe. Waves 2-3 (integration, application) add detail to drill-downs and use cases but don't change the platform story. Starting after Wave 1 gets an early draft while guides are still fresh in context. Iterative refinement matches the overall approach.
**Trade-offs:** Brief may need revision as Wave 2 integration repos (blocks, connectors, desired state) reveal cross-cutting patterns. Acceptable — the brief is a living draft.
**Depends on:** D5 (layered structure means top layers can be written from foundation knowledge alone)
**Sources:** parent/docs/audit/audit-process.md (3 wave priority order)
**Exploration:** quick
**Status:** captured

## D10: Brief lives in parent/docs/

**Choice:** Executive brief and its SVGs live in parent/docs/ alongside PLATFORM.md and INDEX.md
**Alternatives:**
- Standalone repo — isolation but adds another repo to an already large family
- casehubio.github.io — ready to publish but couples writing to site deployment
**Rationale:** parent/docs/ is the established home for platform-level documentation. The brief is a platform-level document. SVGs sit alongside the markdown. No deployment coupling — publication to the site is a separate step when the brief is ready.
**Trade-offs:** parent repo grows larger, but docs/ is already substantial and well-organized.
**Sources:** parent/docs/PLATFORM.md (existing platform-level doc), parent/docs/INDEX.md (discovery index)
**Exploration:** quick
**Status:** captured