# Channel Integration — Brief Notes

> Descriptive name: **Channel Integration**
> Internal repo: connectors
> Modules: 29

## Capabilities

- **Outbound Messaging** — unified `Connector` SPI delivers to Slack, Teams, SMS (Twilio), WhatsApp, Signal, email via `java.net.http.HttpClient` (no vendor SDKs)
- **Inbound Messaging** — dual-pattern: pull-based (`InboundConnector` for IMAP, Discord Gateway, IRC, Signal WebSocket) and push-based (`WebhookInboundConnector` for Slack, Teams, WhatsApp, Twilio webhooks) — both converge to CDI async events
- **Structured Chat** — `ChatPlatform` SPI with 10 capability interfaces (Messaging, Threading, Discovery, Reactions, Presence, Members, ChannelManagement, MemberManagement, MessageHistory, Commands) — graceful degradation for missing capabilities, builder pattern, 5 implementations (Reference, IRC, Discord, Slack, Signal)
- **Calendar Integration** — `CalendarPlatform` SPI with full CRUD, sealed `EventTiming` model (Timed/AllDay), Google Calendar provider
- **Banking Integration** — `BankPlatform` SPI with AISP (accounts, balances, transactions) and PISP (payment initiation) capability sub-interfaces, user-scoped for PSD2 consent, TrueLayer Open Banking provider with consent lifecycle, payment webhooks
- **Email Query/Read** — `EmailPlatform` SPI for mailbox listing, paginated messages, attachment download. Complements outbound (EmailConnector) and push inbound (EmailInboundConnector)
- **Document Management** — `DocumentPlatform` SPI with FileOperations, FolderOperations, SearchOperations, SharingOperations capability sub-interfaces, Google Drive provider
- **Notification Bridge** — connects platform notification delivery system to connector SPI with per-user/per-tenant scoping and digest formatting
- **MCP Tool Surface** — 14+ tools for LLM agents covering all platform SPIs, unified via `@McpDomain("connectors")`
- **Slash Commands** — `CommandHandler` SPI for registering and dispatching slash commands across chat platforms, with immediate and deferred response patterns

## Differentiation

- **No vendor SDKs** — all HTTP-based connectors use `java.net.http.HttpClient` directly. No Slack SDK, no Twilio SDK. This keeps the dependency footprint minimal and avoids SDK version conflicts.
- **Capability-based degradation** — ChatPlatform's builder pattern with auto-degradation means consumers write against the full 10-capability API and it works on every platform, gracefully falling back for unsupported features. No platform-specific branching in consumer code.
- **Five homogeneous platform SPIs** — Chat, Calendar, Bank, Email, Document all follow the same pattern: SPI interface with `@SimulationEligible`, routing service, reference implementation for testing, real provider(s). This consistency means learning one SPI teaches you all five.
- **Capability introspection** — `supports(Class<?>)` on platform SPIs + `UnsupportedCapabilityException` with structured metadata enables LLM self-correction when agents hit unsupported operations.
- **Simulation-ready** — all SPIs carry `@SimulationEligible` with corpus data on the classpath, enabling the platform simulation framework to generate CDI decorators at build time.

## Extent

29 modules covering 5 distinct platform SPIs (chat, calendar, bank, email, document) each with SPI, reference implementation, and at least one real provider. 6 outbound connectors, 8 inbound connectors, 5 chat platform implementations, 14+ MCP tools. The chat SPI alone has 10 capability interfaces with 9 degraded fallback implementations. Open Banking integration includes full PSD2 consent lifecycle, payment initiation with SCA flow, webhook-based status updates with JWS verification, and database-backed consent persistence.

## Consistency Benefit

- All SPIs follow the same pattern as platform-level CDI services: `@ApplicationScoped` beans, routing service with `@All List<T>` discovery, `@DefaultBean` no-ops for graceful absence
- `@SimulationEligible` integrates with platform simulation framework (recursive wrapper generation)
- CDI event bus for inbound message propagation (`@ObservesAsync InboundMessage` / `ReceivedMessage`)
- CloudEvent adapter connects to platform event infrastructure
- Notification bridge integrates with platform `DeliveryChannelRegistry`
- `@McpDomain` unifies API surface across REST/GraphQL/MCP — same operations, three transports

## Vision (from open issues)

- **LocationPlatform SPI** (#126) — places, geocoding, directions for spatial RAG
- **ProjectPlatform SPI** (#124, recently closed) — GitHub issues, milestones, comments
- **ContactsPlatform SPI** (#125, recently closed) — identity sync from external providers
- **Sync support** (#129, recently closed) — CalendarPlatform and DocumentPlatform sync
- **Teams ChatPlatform** (#45) — would complete the enterprise chat trifecta (Slack, Discord, Teams)

The trajectory: from messaging connectors to a universal platform SPI framework covering every external system an agent might interact with.

## Cross-Repo Patterns

- `ConnectorMeshBridge` SPI bridges to qhorus (agentic communications) when on classpath
- `notification-bridge` bridges to platform notification delivery system
- Chat platform implementations feed into engine via `ReceivedMessage` CDI events
- MCP tools make all capabilities available to LLM agents (claudony, openclaw)
- Banking integration demonstrates PSD2-compliant financial operations — relevant for the AML and FSI trading applications