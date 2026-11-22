# Brief Notes — Domain-Aware UI Component Library

> Source repo: blocks-ui (60 components, 9 packages, 479 non-test TS source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Case Lifecycle Visualisation
Visual diagram editors for CaseDefinition YAML (casehub-diagram), SWF workflows (swf-diagram), and HTN plans (htn-diagram). Diagram-workbench provides drill-down navigation across all three. Case-flow-viewer for read-only DAG views with trust score pills and adaptive decision badges. Case-dependency-graph for force-directed relationship visualisation.
- **Differentiation:** Full visual editing of case definitions with live runtime overlay — no competitor provides a visual YAML editor that renders execution state onto the design graph in real time. Three diagram types (case, SWF, HTN) composing through a drill-down stack. The casehub-diagram is both an editor and a live monitor.
- **Extent:** 4 diagram components, 4 graph stencil packages (case, SWF, HTN, org), diagram-workbench for composition. ELK auto-layout, undo/redo, persistence SPI, SVG/PNG export.

### 2. Work & Task Management UI
Work-item-inbox (three-tab perspective: My Work / Claimable / All), work-item-detail (action bar, activity, relations), work-item-workbench (split-pane composition). Worker-task-pane for task claim/respond. SLA indicator with countdown and breach states. Approval-gate for structured decision points with quorum and evidence.
- **Differentiation:** Not just a task list — full work lifecycle UI with SLA integration, M-of-N approval gates, and semantic relation display (outgoing + incoming with type inverses). Worker-task-pane supports workspace definitions for complex task submission.
- **Extent:** 6 work-related components, SSE lifecycle, keyboard shortcuts, queue pill bar.

### 3. Trust & Accountability Dashboard
Trust-score-panel (Bayesian Beta gauge with per-capability breakdown), trust-feedback-display (post-gate delta), routing-rationale (trust-weighted assignment explanation), audit-trail-viewer (Merkle verification, attestations, GDPR erasure), trust-workbench (composite). Contributor-workbench for trust/outcome tracking.
- **Differentiation:** No other UI library renders Bayesian trust scores with per-capability breakdown, routes decisions through trust thresholds with visual explanation, and verifies Merkle chains in the browser. The trust story is end-to-end: score → routing → decision → feedback → audit.
- **Extent:** 6 trust/accountability components, sparkline trends, compact badge modes, dual data modes.

### 4. Structured Communication UI
Channel-activity (12 sub-elements: feed, messages, reactions, input with speech-act selector, emoji picker, threading, channel nav, member panel, topic bar, task/artifact/correlation panels). Conversation-viewer (convergence indicator, common ground, point list/detail — structured deliberation protocol). Commitment-viz (7-state lifecycle with transition badges and range bars). Notification-inbox (bell, tabs, filters, SSE, subscription CRUD, channel preferences, mute, snooze).
- **Differentiation:** Channel-activity is not just a chat widget — it's a structured communication platform with speech-act types, threaded replies, and three consumption tiers. Conversation-viewer renders convergence state and epistemic common ground — a structured deliberation UI that no generic chat component provides. Notification-inbox has full subscription management with per-channel delivery preferences.
- **Extent:** 4 major communication components with dozens of sub-elements, PushController abstraction for transport-agnostic push, DOMPurify security.

### 5. Document Review & AI Collaboration
Document-workbench (9 panels: debate-feed, document-diff, document-timeline, review-tracker, context-gauge, doc-picker, brainstorm-options, brainstorm-picker, workspace-status). Full AI-assisted document review workflow with streaming debate entries, side-by-side markdown diff, review point tracking with batch accept/defer, LLM context window usage indicator.
- **Differentiation:** Purpose-built for multi-agent document review with structured debate. Agent roles (REV/IMP/HUMAN/SUPERVISOR/MODERATOR/SELECTOR), 18 entry types, review point status tracking, brainstorm option cards with converge/end states. No equivalent exists in any web component library.
- **Extent:** 9 tightly integrated panels, all following `configure()` pattern for host integration, SSE reconnect awareness.

### 6. Agent Identity & Personality
Agent-catalog (template browsing with featured cards), agent-manifest-editor (LLM provider/model/credential config), agent-personality-workbench (tabbed catalog + wizard), agent-profile-panel (personality display), avatar-step (faceted personality selector). Agent-avatar-2d package (4 SVG avatar collections). Full agent setup flow from personality selection through LLM configuration.
- **Differentiation:** No other UI component library provides visual agent identity configuration. Personality frameworks drive both visual appearance (avatar) and behavioral parameters. Complete agent setup workflow — personality → appearance → LLM provider → credentials — in composable web components.
- **Extent:** 5 agent identity components, 2 supporting packages (agent-avatar-2d, avatar), 4 avatar collections, profession presets.

### 7. Evolution Conductor UI
Evolution-config (deny-pattern-editor, watch-pattern-editor, gate-policy-editor), evolution-workbench (KPI metrics, streams, inbox, audit trail, health). Operational dashboard for the evolution conductor — the system that manages agent improvement streams with gated progression.
- **Differentiation:** Purpose-built for evolution conductor operations. No other platform has a UI for managing gated agent evolution streams with deny patterns, watch patterns, and per-stage gate policies. Agent methodology observability (#1181).
- **Extent:** 2 component packages, EvolutionApi REST client, SSE push support.

### 8. Operations & Infrastructure
Cluster-panel (K8s cluster management), service-card (service health), topology-viewer (service topology graph), reconciliation-status (desired-state reconciliation), dimension-dashboard (multi-dimension severity). Ops-focused components for infrastructure visibility.
- **Differentiation:** These components bridge the gap between the case management platform and its operational infrastructure. Reconciliation-status ties directly to the desiredstate repo's reconciliation loop. Topology-viewer renders service dependencies with live status.
- **Extent:** 5 ops components, all with PushMixin for live updates, status-coloured rendering.

### 9. Composable Layout System
Split-workbench (draggable divider, responsive single-panel, localStorage persistence), list-pane (pages-table wrapper with DataSourceMixin), detail-pane (lazy tab panels), grouped-data-view (pages-grouped-view wrapper). Case-explorer (universal entity browser with registration-based types). Session-workbench for claudony session management.
- **Differentiation:** Not a layout framework — a composable panel system where every component works standalone AND embedded via pages hostPanel. Three consumption tiers (standalone, panel-hosted, inline data) are consistent across all components.
- **Extent:** 6 layout components, keyboard shortcuts, entity presets (case instances, workers, definitions, gates, channels).

## Cross-Repo Patterns
- **blocks (backend):** blocks-ui renders what blocks computes — case definitions authored in blocks are visualised in casehub-diagram, SWF workflows in swf-diagram, HTN decompositions in htn-diagram
- **pages (infrastructure):** All components built on casehub-pages primitives — pages-table, pages-event, pages-graph-canvas, pages-diagram-core, DataSourceMixin, pages-ui-tokens
- **engine:** Case lifecycle, routing decisions, worker dispatch — rendered by case-explorer, routing-rationale, worker-task-pane
- **work:** Work items, SLA, delegation — rendered by work-item-workbench, sla-indicator, approval-gate
- **ledger:** Audit trails, trust scores — rendered by audit-trail-viewer, trust-score-panel
- **qhorus:** Channel activity, conversation protocol — rendered by channel-activity, conversation-viewer
- **eidos:** Agent identity, personality — rendered by agent-personality-workbench, agent-profile-panel
- **desiredstate:** Reconciliation status — rendered by reconciliation-status component
- **iot/ops:** Cluster management, service topology — rendered by cluster-panel, topology-viewer
- **connectors:** Chat platform SPI — channel-activity promoted from connectors chat-demo

## Vision
- #218: Merge catalog and wizard into unified personality selector
- #212: Agent profile character sheet with inline editing
- #213: Agent relationship editor with table-based management and visualization
- #117: Conversation-viewer generalization path for document-workbench
- Ongoing: diagram-core capabilities continuing to expand in pages-diagram-core