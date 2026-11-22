# Brief Notes — Device Management & IoT

## Capability Area

**Descriptive name:** Device Management & IoT
**Pillar:** Automation (device abstraction + command dispatch), Collaboration (AI resolution + human escalation), Coordination (bridge mesh + situation awareness)

## What It Does

Provides a unified, typed device abstraction layer aligned with the Matter Device Type Library. 11 concrete device types (switch, light, thermostat, sensor, presence, power, lock, cover, media player, fan, camera) with a polymorphic hierarchy. Dual-provider architecture (Home Assistant + OpenHAB) with auto-discovery (mDNS/SSDP), real-time state streaming (WebSocket/SSE), and command dispatch. Edge-to-cloud bridge with durable store-and-forward and 7-variant sealed wire protocol. Operational console with case-driven incident response, CBR-powered resolution, multi-turn LLM agent, and situation awareness ganglia.

## Differentiation

- **Matter-aligned type system:** Not a string-property bag. 11 typed device classes with capability-based change detection. Competitors (Home Assistant integrations, OpenHAB bindings) give you raw data; CaseHub gives you a typed, capability-aware device model.
- **Edge-to-cloud bridge:** Sealed wire protocol (7 variants), durable store-and-forward for crash resilience, multi-tenant device ID namespacing. Not just a WebSocket relay — a protocol-aware, auditable bridge.
- **AI resolution pipeline:** LLM-driven autonomous resolution agent with multi-turn conversation, CBR-powered similarity matching, risk classification, and automatic escalation. Not a chatbot — a full resolution pipeline with human-in-the-loop.
- **CBR for IoT:** 4 feature schemas, temporal recency weighting, false-positive suppression, work item outcome prediction, case triage routing. Learns from every resolution.
- **Simulation as first-class:** Temporal simulation profiles, corpus seeding, scenario runtime. Not an afterthought — simulation and real operation use the same APIs.

## Extent

12 modules (+ root POM). 50 Java files in webapp alone. 5 ganglia (standard) + 2 DroolsCEP ganglia. 4 case types. 4 CBR feature schemas. Multi-turn LLM agent with configurable conversation mode. Full REST API surface (devices, cases, situations, work items, health, providers, bridge, simulation). MCP tools (4 tools). SSE streaming. Pages scenario infrastructure with 3 bundled scenarios. Docker multi-arch deployment (ARM64 + x86_64).

## Consistency Benefit

- **CDI throughout:** DeviceProvider, DeviceRegistry, ganglia, CBR, AI agent — all CDI beans, same lifecycle as every other CaseHub module
- **Shared platform SPIs:** Uses CaseInputContributor (ras-api), CaseQueueService (engine), AgentProvider (platform), SimulationCorpus (platform), NotificationBridge (platform), McpResourceRegistry (platform), @PlatformStream (platform)
- **Tenant-scoped:** Same tenancy model as all other repos — single `casehub.iot.tenancy-id`, McpIdentityContext for MCP tenancy filtering
- **Audit trail:** BridgeAuditEvent integrates with the platform audit pattern. IoTCommandAuditEvent for command audit.
- **CloudEvent integration:** StateChangeEvent → CloudEvent via IoTCloudEventAdapter, same event model as platform

## Vision (from open issues)

- **Desired state integration (#121 epic):** IoT NodeProvisioner as desired-state adapter — declare device state, reconciliation drives reality. Closes the Declaration → Convergence arc for physical devices.
- **Topology visualization (#119):** Logical (spatial tree) and physical (operational graph) dual-view with drift visualization. Visual understanding of device relationships.
- **Scenario concurrency (#117):** Map scenario concurrency model to distributed device command orchestration. From demo to production automation.
- **Model fine-tuning (#84):** Custom model fine-tuning and prompt versioning for the AI resolution agent. Domain-specialized LLM for IoT.

## E2E Use Case Fragment

**Home automation incident response:** Situation ganglia detect anomaly (sustained temperature rise, multi-room motion at night) → case created → CBR retrieves similar past resolutions → AI agent opens multi-turn conversation with MCP tools to diagnose → proposes resolution plan → risk-classifies actions → executes safe actions autonomously → escalates high-risk actions to human work item → human resolves → outcome recorded to CBR → future similar incidents resolve faster.

This is the full Declaration → Intelligence → Control → Convergence → Operations loop applied to physical devices. No competitor offers this end-to-end for IoT.

## Platform Capabilities Leveraged

| Capability | Usage |
|-----------|-------|
| Case Orchestration | 4 case types for incident response |
| Situation Awareness | 7 ganglia (5 standard + 2 DroolsCEP) |
| Case-Based Reasoning | 4 feature schemas, similarity matching, suppression, prediction |
| Human Task Management | Work items for escalated resolutions |
| Agentic Communications | Multi-turn LLM conversation via AgentProvider |
| Trust & Accountability | Bridge audit trail, command audit events |
| Desired-State Reconciliation | NodeProvisioner (planned, #121) |
| Notification Pipeline | Household notifications via subscription engine |
| Shared Platform Services | Identity, tenancy, expressions, MCP tools, SSE streaming, simulation |