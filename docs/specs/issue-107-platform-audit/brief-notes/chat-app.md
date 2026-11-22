# Brief Notes — Chat Workbench

## Capability Area

**Descriptive name:** Structured Chat & Collaboration
**Pillar:** Collaboration (human-agent messaging + commitment tracking), Coordination (speech-act correlation + topic-based threading)

## What It Does

Runnable chat workbench application that wires together qhorus runtime (messaging, channels, topics, commitments, gateway), blocks-ui-channel-activity (reusable chat components), and pages (auth, push protocol, theming) into a complete structured communication experience. Two @McpDomain API classes expose tri-channel (REST + GraphQL + MCP) endpoints for messages and presence. CDI decorator pattern for broadcast-on-mutation. HumanParticipatingChannelBackend implementation for qhorus gateway fan-out. Space-based channel hierarchy with drag-and-drop reordering. Three-layout responsive UI (desktop/tablet/phone) with edge-swipe gestures.

## Differentiation

- **Structured messaging, not just chat:** Messages carry speech-act types (COMMAND, QUERY, INFORM, etc.), correlation IDs, artefact references, and topic assignments. Every conversation thread is traceable as a correlation chain with commitment lifecycle tracking. This is chat infrastructure for accountable coordination, not a Slack clone.
- **Commitment lifecycle visualisation:** Inline range bars show commitment spans in the feed. Task panel groups obligations as Overdue/Active/Completed. Correlation panel renders the full chain with speech-act badges, state pills, and transition history. No other chat tool visualises normative commitment state inline.
- **Gateway fan-out pattern:** ChatAppChannelBackend registers as a human-participating backend. The same channel can have multiple backends (human chat, LLM agent, connector) — qhorus routes outbound messages to all registered backends. The chat app is one surface for multi-backend conversations.
- **Tri-channel API from annotations:** @McpDomain classes generate REST, GraphQL, and MCP endpoints from one set of annotated methods. LLM agents can operate on chat via MCP tools without any additional integration code.

## Extent

Single-module Maven project. 11 Java source files (6 domain + 5 record DTOs), 10 TypeScript source files. 2 @McpDomain classes with 7 operations. BroadcastingChannelManager CDI decorator. 7 WebSocket dataset topics (channels, topics, messages, members, presence, reactions, commitments). 6 dock panels (Channels, Members, Tasks, Correlation, Artifacts, Settings). 4 seed channels. 128 tests (18 Java + 110 TypeScript).

## Consistency Benefit

- **CDI throughout:** ChatAppChannelBackend, BroadcastingChannelManager, ChatAppCurrentPrincipal, API classes — all CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses CurrentPrincipal (platform-api), @McpDomain (platform-api), HumanParticipatingChannelBackend (qhorus-api), ConsumerMessaging/ChannelManager/TopicManager/etc (qhorus-api), QhorusWebSocketBroadcaster (qhorus-push)
- **Tenant-scoped:** Same tenancy model — ChatAppCurrentPrincipal extracts tenant from JWT, defaults to "chat-app"
- **Pages runtime:** Uses pages-auth for JWT dev-auth, pages-ui-tokens for theming, pages-data for WebSocket lifecycle, pages-runtime for panel registration
- **blocks-ui components:** Consumes channel-activity (feed, nav, input, member panel, topic bar, controllers) and commitment-viz (range-bar, state-pill, range-decorator) — same components available to all CaseHub apps

## Vision (from open issues)

- **Floating panels (#32):** Evaluate multi-window chat with detachable panels. Extends the dock strip model to floating windows.
- **Touch interactions (#2):** Long-press context menus and swipe-to-reply for mobile-native UX.

## E2E Use Case Fragment

**Multi-party coordination with accountability:** Project manager posts a COMMAND message in a channel → qhorus creates a commitment with deadline → assignee receives the obligation in their task panel → assignee replies with INFORM updates (each tracked in correlation chain) → manager reviews commitment state transitions inline in the feed → commitment resolves or escalates based on deadline → full speech-act chain preserved as auditable record of who committed to what, when, and how it resolved.

This demonstrates the Coordination pillar: structured communication where every message has semantic type, correlation chains track conversation threads, and commitment lifecycle provides accountability without manual tracking.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Agentic Communications | HumanParticipatingChannelBackend in qhorus gateway fan-out |
| Commitment Tracking | Correlation chains with speech-act types, commitment lifecycle (acknowledge → resolve/expire) |
| Push Infrastructure | QhorusWebSocketBroadcaster from qhorus-push, 7 dataset topics |
| Tri-Channel API | @McpDomain generates REST + GraphQL + MCP from 2 annotated classes |
| Identity & Auth | ChatAppCurrentPrincipal (CurrentPrincipal SPI), pages-auth JWT dev-auth |
| UI Component Library | blocks-ui-channel-activity (6 components + 6 controllers), commitment-viz (3 components) |
| Pages Runtime | Panel registration, layout store, theming, WebSocket lifecycle |
| Space Hierarchy | Channel organisation via SpaceService from qhorus runtime |