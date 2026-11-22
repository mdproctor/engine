# Brief Notes — Document Review & Adversarial Debate

## Capability Area

**Descriptive name:** Document Review & Adversarial Debate
**Pillar:** Collaboration (multi-agent structured debate, human-in-the-loop), Coordination (channel-based conversation protocols, pipeline orchestration), Automation (autonomous debate, voice-to-notes pipeline)

## What It Does

MCP-driven document review platform with five operational modes: document review (A/B comparison with grounded Q&A), adversarial debate (multi-agent structured conversation with round-based protocols, sub-agent dispatch, and human-in-the-loop), brainstorming (interactive option exploration with convergence tracking), review pipeline (multi-dimension sequential review with checkpoint tracking), and voice capture (browser audio recording with STT transcription and Obsidian vault storage via composable facets). 52 MCP tools across 8 classes. Browser UI is a casehub-pages workbench with 14 Lit panels. Electron shell for desktop use. All conversation routed through Qhorus channels with typed metadata encoding.

## Differentiation

- **MCP-native document review:** The entire workflow is MCP-driven — any MCP client (Claude Code, Claudony, custom) can orchestrate reviews, debates, and brainstorming. No UI required. The UI is a real-time observer, not the control plane.
- **Structured adversarial debate:** 17 entry types, 6 agent roles, round-based conversation with sub-agent dispatch (6 handler types), autonomous orchestration, context tracking, and workspace replay. Not a chat — a formal debate protocol with typed speech acts.
- **Selection-scoped threads:** Location-anchored conversations within debates — threads tied to specific text selections with OPEN/RESOLVED lifecycle. Nearby thread detection prevents duplicate discussions.
- **Composable facets:** `Facet` SPI for independently activatable session capabilities (voice, notes, future: citation, image). Each facet brings its own MCP tools, artifacts, and state. Facets compose — voice auto-activates notes.
- **Human-in-the-loop as first-class:** 5 HIL endpoints (comment, raise, override, prioritise, batch accept/defer). Human decisions written to workspace files via `DecisionFileWriter`. HUMAN is a proper agent type, not a fallback.
- **Voice-to-vault pipeline:** Browser MediaRecorder → audio upload → STT transcription → cleanup pipeline → YAML frontmatter notes → Obsidian vault. End-to-end voice capture without leaving the review workflow.

## Extent

3 Maven modules (api, runtime, claude-agent). 114 main Java source files, 104 test Java files, 5 TypeScript panel files. 52 MCP tools across 8 classes. 5 REST resources (13 endpoints). 2 WebSocket endpoints (pages-push protocol + PTY terminal). 4 reviewer personas seeded via Eidos AgentRegistry. 6 sub-agent handler types. 2 built-in facets (VoiceFacet, NotesFacet). Quarkus 3.34.3, Java 21. Electron shell for desktop use.

## Consistency Benefit

- **Qhorus throughout:** All conversation (review, debate, threads, autonomous) flows through Qhorus channels with typed metadata encoding. Same channel primitives as every other CaseHub communication system.
- **Eidos reviewer identity:** 4 reviewer personas registered via AgentRegistry with structured dispositions, capabilities, and briefings. Same identity model as platform agents.
- **Platform AgentProvider SPI:** LLM invocation for sub-agents via `PlatformDebateAgentProvider`. Same agent dispatch as every other CaseHub application.
- **Pages workbench:** UI built on casehub-pages with pages-push WebSocket protocol, pages-component lifecycle, pages-data subscriptions. Same frontend architecture as other CaseHub apps.
- **Blocks conversation protocol:** Context tracking, message meta encoding, channel agent dispatch, conversation orchestrator — all from casehub-blocks shared library.
- **SpeechToTextService SPI:** STT via blocks SPI — pluggable provider, same interface across the platform.

## Vision (from open issues)

- **CitationFacet (#119):** Composable reference lookup and citation management as a facet.
- **ImageFacet (#118):** Composable image generation via external APIs as a facet.
- **Voice capture completion (#120-122):** Full voice-first drafting mode — speak ideas, STT transcribes, cleanup pipeline structures, vault stores, MCP tools query.
- **Facet ecosystem expansion:** The `Facet` SPI is designed for open-ended capability composition. Future facets (citation, image, code, search) each bring their own MCP tools and artifacts.

## E2E Use Case Fragment

**Design spec review pipeline:** Author uploads spec → `start_pipeline` creates multi-dimension review (structural, content, readability, completeness) → per-dimension `WorkspaceWatcher` monitors progress → reviewer agents conduct adversarial debate on each dimension with selection-scoped threads → human reviews flagged points via HIL actions (comment, override, batch accept/defer) → decisions written to workspace files → `export_debate_summary` produces final report. During review, voice facet captures verbal observations → STT transcribes → cleanup pipeline structures → vault stores notes alongside spec. All conversation flows through Qhorus channels — full audit trail of who said what, when, and why.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Agentic Communications (Qhorus) | All conversation through typed channels — review Q&A, debate rounds, thread replies, autonomous orchestration |
| Agent Identity (Eidos) | 4 reviewer personas with structured dispositions and capabilities via AgentRegistry |
| Agent Infrastructure (Platform) | LLM invocation via AgentProvider SPI for sub-agent dispatch |
| Conversation Protocol (Blocks) | Context tracking, message meta encoding, channel agent dispatch, conversation orchestrator, bounded projection |
| Speech-to-Text (Blocks) | SpeechToTextService SPI for voice transcription |
| Pages Workbench (Pages) | 14 Lit panels, pages-push WebSocket protocol, pages-data subscriptions |
| Push Protocol (Pages) | Typed PushMessage wire format for real-time UI updates |

## App Profile

```yaml
app_profile:
  domain: "Document review, adversarial debate, and voice-first drafting"
  capabilities:
    - "MCP-driven multi-agent adversarial debate with 17 entry types and 6 agent roles"
    - "Document A/B comparison with grounded reviewer Q&A"
    - "Selection-scoped conversation threads with anchor detection"
    - "Composable facet architecture for session capabilities"
    - "Voice capture with STT and Obsidian vault storage"
    - "Multi-dimension review pipeline orchestration"
    - "Human-in-the-loop with 5 action types and workspace persistence"
    - "Brainstorming with option exploration and convergence tracking"
  platform_leverages:
    - {capability: "Agentic Communications", usage: "Qhorus channels for all structured conversation"}
    - {capability: "Agent Identity", usage: "Eidos AgentRegistry for 4 reviewer personas"}
    - {capability: "Agent Infrastructure", usage: "Platform AgentProvider for sub-agent LLM dispatch"}
    - {capability: "Conversation Protocol", usage: "Blocks context tracking, meta encoding, orchestration"}
    - {capability: "Speech-to-Text", usage: "Blocks SpeechToTextService for voice transcription"}
    - {capability: "Application Surface", usage: "Pages workbench with 14 Lit panels and push protocol"}
  status: active
```