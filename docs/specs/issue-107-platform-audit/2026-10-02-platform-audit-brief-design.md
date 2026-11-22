# Platform-Wide Guide Audit, Knowledge Index & Executive Brief

> **Issue:** casehubio/casehub-ops#107
> **Branch:** issue-101-platform-brief-prep
> **Status:** Design
> **Decisions:** See `decisions.md` (D1–D9)

---

## 1. Purpose

Build an accurate, comprehensive understanding of the CaseHub platform —
what it is, what it does, the problems it solves, and where it fits in the
market — then express that understanding as a layered executive brief with
SVG illustrations.

The work has two modes: *audit* (verify and revise per-repo documentation
against code reality) and *synthesis* (distill cross-platform understanding
into themes, positioning, and narrative). Both feed the brief. Neither is
sufficient alone.

---

## 2. Phasing

### Phase 1: Structural Index (one-off Python scan)

Scan all product repos, blog sites, and workspace repos. Output:
`parent/docs/audit/docs-inventory.yaml` — a flat YAML file of every .md
file with structural metadata. No content interpretation.

**Purpose:** The map. Every subsequent phase navigates using this index
instead of doing full repo scans.

### Phase 2: Guide Audit (3 waves)

Verify and revise consumer guides, contributor guides, ARC42STORIES, and
CLAUDE.md across all product repos. Update parent indexes. Accumulate brief
source material.

- **Wave 1 (foundation):** platform, engine, worker, ledger, connectors,
  work, qhorus, eidos, neocortex, iot
- **Wave 2 (integration):** ras, desiredstate, blocks, blocks-ui, claudony,
  openclaw, workers, ops, pages
- **Wave 3 (application):** devtown, aml, clinical, life, drafthouse,
  quarkmind, soc, fsitrading, chat-app

Repos within a wave can be audited in parallel.

### Phase 3: LLM Iterative Passes (knowledge extraction)

Using the structural index, LLM reads files in priority order. Each pass
augments index entries with semantic fields and tracks `extraction_pct`.
Reads GitHub issues, specs, research docs, and blogs for forward-looking
vision and cross-cutting themes.

### Phase 4: Executive Brief (starts after Wave 1)

Draft begins once foundation repos are audited. Refines continuously as
Waves 2–3 and LLM passes add depth. Lives in `parent/docs/`.

---

## 3. Structural Index

### Location

`parent/docs/audit/docs-inventory.yaml`

### Entry Schema — Two Tiers

**Tier 1: Python-generated (structural)**

```yaml
- path: docs/guides/contributor-guide.md
  repo: engine
  source: project
  headings:
    - {level: 1, text: "Engine Contributor Guide"}
    - {level: 2, text: "Module Structure"}
    - {level: 2, text: "SPI Architecture"}
    - {level: 2, text: "Routing Architecture"}
  last_modified: 2026-09-15
```

| Field | Values | Purpose |
|-------|--------|---------|
| `path` | Relative to repo root | File location |
| `repo` | Short repo name | Repo-scoped queries |
| `source` | `project` / `blog-casehub` / `blog-mdproctor` | Canonical source identification |
| `headings` | Array of `{level, text}` | Table of contents for targeted reading |
| `last_modified` | ISO date from git log | Staleness detection |

**Tier 2: LLM-augmented (semantic, added over passes)**

```yaml
  type: guide
  audience: contributor
  summary: "Module structure, handler pipeline, routing, DAG execution"
  topics: [routing, planning, workers, CDI, SPI, tenancy]
  extraction_pct: 80
```

| Field | Values | Purpose |
|-------|--------|---------|
| `type` | `guide` / `spec` / `research` / `blog` / `adr` / `readme` / `index` | Type-scoped queries |
| `audience` | `consumer` / `contributor` / `both` / `internal` | Audience filtering |
| `summary` | One-line content description | Quick relevance check |
| `topics` | Capability keyword list | Cross-repo topic lookup |
| `extraction_pct` | 0–100 | How much content has been processed |

**Application entries** get an additional `app_profile` block:

```yaml
  app_profile:
    domain: "AI-assisted software development"
    capabilities:
      - "PR review with trust-weighted reviewer routing"
      - "Merge queue with batch composition and bisection"
    platform_leverages:
      - {capability: "Case Orchestration", usage: "PR review lifecycle as case plans"}
      - {capability: "Trust & Audit", usage: "Reviewer trust scoring via attestation"}
      - {capability: "Case-Based Reasoning", usage: "Reviewer matching from history"}
    status: active
```

### Sources Scanned

| Source | What's scanned | Source value |
|--------|---------------|-------------|
| Product repos (slot 207) | `docs/**/*.md`, `README.md`, `CLAUDE.md` | `project` |
| Workspace repos (`wsp-*`) | `blog/**/*.md`, `specs/**/*.md` | `project` |
| `casehubio.github.io` | `_articles/*.md` | `blog-casehub` |
| `mdproctor.github.io` | `_articles/*.md`, `_posts/*.md`, `_notes/*.md` (CaseHub-related only) | `blog-mdproctor` |
| `parent` | `docs/**/*.md` | `project` |
| Application repos | Also: `pom.xml`, case definition YAMLs, agent config | `project` |

### Dedup Rules

1. Same filename in workspace blog/ and casehubio.github.io → keep blog-site version
2. Parent `docs/repos/*` subtree copies → excluded entirely (canonical versions in each repo are already indexed)
3. Slot duplicates (same repo in multiple slots) → use slot 207 only

### Build Script

Python. Runs from slot 207 root. For each repo:
1. Walk filesystem for `.md` files in scanned paths
2. Extract headings via `^#{1,6}\s+` regex
3. Get `last_modified` from `git log -1 --format=%aI <file>`
4. Apply dedup rules

Output sorted by repo then path. No content interpretation — that's LLM work.

---

## 4. Guide Audit Process

### Ground Truth Hierarchy

1. **Code** (classes, SPIs, interfaces via IntelliJ MCP) — the only thing you trust
2. **Git history** — what changed, when
3. **GitHub issues** (open + closed) — what's delivered, what's planned
4. **Specs and research .md files** — design rationale, forward-looking architecture
5. **Blog entries** — narrative context, design thinking
6. **Existing guides** — starting point, assumed stale until verified

### Per-Repo Audit Flow

1. **Code scan** — IntelliJ MCP: classes, SPIs, module structure. Ground truth.
2. **Git history delta** — changes since guide was last updated
3. **Issue mining** — closed issues (delivered), open issues (vision)
4. **Blog/spec/research scan** — structural index headings find relevant entries
5. **Guide revision** — rewrite consumer-guide, contributor-guide, ARC42STORIES
   against verified reality. Add missed capabilities. Remove unsupported claims.
6. **Brief material accumulation** — flag capabilities, use cases, vision items,
   cross-repo patterns for the executive brief

### Parent Index Updates (after each wave)

| Document | Update scope |
|----------|-------------|
| `consumer-index.md` | Repo summaries, key types |
| `contributor-index.md` | Module counts, dependencies |
| `capabilities.md` | Missing capabilities, stale links |
| `APPLICATIONS.md` | Domain descriptions, platform deps, status |

**Never touch `docs/repos/*`** — subtree SHA links, updated by subtree sync only.

### Audit Tracking

Per-repo status in `docs-inventory.yaml`:

```yaml
audit_status:
  - repo: engine
    wave: 1
    consumer_guide: done
    contributor_guide: done
    arc42stories: done
    claude_md: done
    last_audited: 2026-10-05
    notes: "17 modules verified, 3 new SPIs added"
```

---

## 5. LLM Iterative Passes

### Pass Model

The LLM uses the structural index to navigate. Each pass:

1. Select files to read (by repo, type, topic, or extraction_pct)
2. Read file content
3. Augment the index entry: fill/update type, audience, summary, topics
4. Update `extraction_pct`
5. For application repos: build `app_profile`
6. Record brief-relevant findings: capabilities, themes, cross-repo patterns

### Priority Order

1. Foundation repo guides (Wave 1 audit material)
2. Specs and research docs (design rationale and vision)
3. Blog entries (narrative and positioning)
4. Application repo guides and configs (use case evidence)
5. Remaining .md files (READMEs, ADRs, indexes)

### What the Passes Produce

- Fully augmented docs-inventory.yaml (all semantic fields populated)
- Brief source notes per capability area
- Cross-repo pattern observations
- Vision items from open issues and specs
- Gap identification (capabilities without documentation)

---

## 6. Executive Brief Structure

### Positioning

**Frame:** AI Harness for Digital Transformation through Automation,
Coordination and Collaboration (AIH-DT-ACC). "Harness" is the right
word — it channels and controls AI power, not just provides it.

**Anchor:** Enterprise-class Java platform for automation, coordination,
and collaboration — the most productive and powerful AI Harness for
digital transformation.

**Breakthrough:** YAML makes this power accessible to domain experts —
complex multi-agent orchestration without writing code.

**Meta-differentiator: Written by LLMs for LLMs.** The platform is
AI-native by design. Every API, DSL, SPI, and MCP tool is designed so
that LLMs can effectively operate the platform. This isn't retrofitted
AI support — the entire development process uses LLMs, and the platform
is built for LLM operability from the ground up.

**Platform-level differentiator: Breadth of homogeneous consistency.**
This isn't bolted-together best-of-breed tools. Every capability shares
CDI, event model, type system, conventions, tenancy model, and audit
trail. Capabilities compose without integration glue. No competitor
matches this breadth with this consistency.

**Market position:** Not a lightweight Python agent framework. Not legacy
BPMN middleware. Enterprise-grade + agent-native + accountability-built-in
+ accessible through YAML + homogeneously consistent across the full
capability surface. That combination doesn't exist elsewhere.

### Lifecycle Spine (the E2E story)

The brief's central narrative follows a complete lifecycle arc:

```
Declaration → Intelligence → Control → Convergence → Operations
     ↑                                                    │
     └────────────────── feedback loop ───────────────────┘
```

| Stage | What happens | Platform capabilities |
|-------|-------------|----------------------|
| **Declaration** | Define intent in YAML: cases, agents, topology, bindings | Manifest schema, case definitions, agent config, app assembly |
| **Intelligence** | LLMs execute with sophisticated agentic patterns | Supervisor, debate, voting, HTN planning, agent mesh, speech acts |
| **Control** | Enterprise-grade execution with full accountability | Planning strategies, oversight gates, trust routing, tamper-evident ledger |
| **Convergence** | Declare desired state; reconciliation drives reality toward it | Reconciliation loop, transition planner, lifecycle manager |
| **Operations** | Situation awareness detects drift, triggers corrective action | Ganglion evaluation, case triggers, operational monitoring |

The loop closes: Operations feeds back into Declaration. Situations
trigger new cases. The platform is self-correcting.

### Brief Layers

**Layer 0 — Strategic Positioning**
- Tagline
- Problem statement (what's broken in the market)
- Key differentiators (3–5 positions, not features):
  - Breadth of homogeneous consistency
  - Written by LLMs for LLMs (AI-native design)
  - End-to-end lifecycle (Declaration → Operations)
  - Accountability by default
  - Domain portability
- Themes (AIH-DT-ACC, accessible agentic AI, enterprise depth with
  YAML accessibility, cognitive architecture)
- Market fit vs. LangChain, CrewAI, Camunda, Kubernetes, etc.
- Platform overview SVG

**Layer 1 — Capability Areas (three pillars)**

Organised under the three pillars, using descriptive names:

*Automation:*
- Case Orchestration (engine)
- Task Execution (worker, workers)
- Desired-State Reconciliation (desiredstate)
- Goal-Directed Planning (engine planning module)

*Coordination:*
- Agentic Communications Mesh (qhorus)
- Agentic Orchestration Patterns (blocks)
- Agent Identity & Cognition (eidos, neocortex)
- Fleet Management (claudony, openclaw)

*Collaboration:*
- Human Task Management (work)
- Channel Integration (connectors)
- Situation Awareness (ras)
- Trust & Accountability (ledger)

*Cross-cutting:*
- Shared Platform Services (platform)
- Web Framework & Components (pages, blocks-ui)
- YAML Application Framework (scaffold)

Each area gets:
- **Description** — what it does (2–3 sentences)
- **Differentiation** — what makes this better/different than alternatives
- **Extent** — how deep the implementation goes (not a thin wrapper)
- **Consistency benefit** — how it gains from the shared platform model
  (CDI, tenancy, events, audit)
- **Architecture SVG** — components and data flow
- **Connections** — how it composes with other areas

**Layer 2 — E2E Use Cases**

3–5 cross-cutting narratives. Each shows:
- The business problem
- How capabilities compose through the lifecycle stages
- Step flow SVG
- What this looks like without CaseHub (the contrast)

Use case domains: regulated investigation (AML), clinical coordination,
software development automation, home/life automation, security operations.

**Layer 3 — Vision**

Where the platform is headed. From open GitHub issues, specs, research.
Not a roadmap — the trajectory. What becomes possible when current
in-progress work lands.

**Layer 4 — Drill-downs**

Per capability area detail pages. Technical audience reads here;
executive audience stays in Layers 0–2.

### Naming Convention

| Internal name | Brief name |
|--------------|------------|
| engine | Case Orchestration Engine |
| qhorus | Agentic Communications Mesh |
| neocortex (RAG) | Corpus Knowledge & Retrieval |
| neocortex (CBR) | Case-Based Reasoning |
| neocortex (inference) | On-Device Inference |
| eidos | Agent Identity & Behaviour |
| blocks | Agentic Orchestration Patterns |
| claudony | Fleet Manager (Claude) |
| openclaw | Fleet Manager (OpenClaw) |
| ledger | Trust & Accountability Ledger |
| work | Human Task Management |
| ras | Situation Awareness |
| desiredstate | Desired-State Reconciliation |
| connectors | Channel Integration |
| pages | Web Component Framework |
| blocks-ui | Shared Domain Components |
| scaffold | YAML Application Framework |
| platform | Shared Platform Services |

This is a starting point. Refined during the audit as capabilities
are verified and named precisely.

### SVG Strategy

Every layer gets diagrams. Include aggressively — prune later.

| Layer | SVG types |
|-------|----------|
| Layer 0 | Platform overview (all capability areas, lifecycle arc) |
| Layer 1 | Per-area architecture (components, data flow) |
| Layer 2 | Per-use-case flow (step sequences, capability composition) |
| Layer 3 | Vision timeline / capability evolution |
| Layer 4 | Per-capability detail (class relationships, SPI wiring) |

SVGs live in `parent/docs/images/brief/` alongside the brief markdown.

---

## 7. Constraints

- **Never write to `parent/docs/repos/*`** — subtree SHA links only
- **Code is ground truth** — all guide claims verified via IntelliJ MCP
- **Python does structure, LLM does meaning** — no Python summarisation
- **Capability names, not product names** — in the brief and its SVGs
- **CaseHub-related only** for mdproctor.github.io blog entries
- **Dedup across sources** — canonical project version wins

---

## References

- parent/docs/audit/audit-process.md — existing 7-step per-repo audit procedure
- parent/docs/guides/documentation-alignment.md — 5-step alignment procedure, doc hierarchy
- parent/docs/INDEX.md — existing platform discovery index
- parent/docs/consumer-index.md — per-repo consumer guide links
- parent/docs/contributor-index.md — per-repo contributor guide links
- parent/docs/capabilities.md — capability-to-repo-to-chunk routing
- parent/docs/APPLICATIONS.md — application repo map with domains and dependencies
- casehubio/casehub-ops#107 — issue body with scope and phased approach
- specs/issue-107-platform-audit/decisions.md — D1–D10