# Brief Notes — Operational Desired-State Console

> Source repo: ops (10 modules, 316 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Agent Topology Desired State
Declarative desired-state management for CaseHub deployment topology — agents, channels, case types, trust policies, endpoints, and RAS detection rules. Six node types compiled into a reconciliation graph. Adaptive topology recompiles when RAS situations change (scale, add, update actions with hysteresis).
- **Differentiation:** No competitor treats deployment topology as a desired-state reconciliation problem. Agent topology is self-healing — situation-driven recompilation means the platform adapts its own agent pool in response to detected conditions without human intervention. Hysteresis prevents oscillation.
- **Extent:** 30 source files. 5 drift checkers (one per node type with field-by-field comparison), 5 provision handlers, adaptive situation recompiler with CDI event bridge + 5-minute safety-net poll.

### 2. Infrastructure Provisioning & Reconciliation
Three-mode infrastructure provisioning (standalone, Terraform augmentation, Ansible augmentation) with 10 sealed InfraNodeSpec types covering K8s resources, cloud infrastructure, and IaC tools. Full plan/apply model with approval workflow.
- **Differentiation:** Not a Terraform wrapper — wraps Terraform AND Ansible with a unified audit trail, reconciliation loop, and human governance layer. The approval workflow (risk classification → authorization → persistent plan store) is the same pattern used across all ops domains.
- **Extent:** 11 source files. StandaloneBackend (in-memory) implemented. TerraformBackend/AnsibleBackend planned. 8 node types monitored by ThresholdFaultPolicy.

### 3. Regulatory Compliance Posture
Strategy-based evidence collection for SOC2-TypeII, GDPR, EU-AI-Act Art.12, DORA, NIS2, ISO27001. Four evidence collectors (file-existence, certificate-expiry, config-hash, log-directory). Framework registry with bidirectional lookup. Tamper-evident compliance records via ledger integration.
- **Differentiation:** Compliance is a reconciliation problem — not a checklist. Each control becomes a desired-state node. Evidence collection is pluggable via strategy key routing. Human-gated controls require explicit review. All evidence records are tamper-evident via the ledger.
- **Extent:** 19 source files. 4 evidence collection strategies. Posture service aggregates cross-control status per framework.

### 4. Container Lifecycle Management
Podman container provisioning via REST socket API with full desiredstate SPI quad. Compiles deployment descriptors to container/network/volume graphs. Real-time event stream subscription for drift detection.
- **Differentiation:** Extends the desired-state reconciliation model to local container environments. Same SPI quad pattern used for K8s (fabric8) and containers (Podman) — consistent operations model across infrastructure scales.
- **Extent:** 19 source files. PodmanClient via Vert.x WebClient, 4 container node types, active event stream with exponential backoff reconnect. ContainerNodeSpecFactoryProvider bridges to YAML frontend.

### 5. Kubernetes Operations Console
Full application lifecycle management: DRAFT → DEPLOYING → RUNNING/DEGRADED → DECOMMISSIONING → DECOMMISSIONED. Multi-cluster K8s integration with fabric8, real-time drift detection via Watch API, and 7 fully-implemented case descriptors for automated operational responses.
- **Differentiation:** The ops console doesn't just deploy — it runs the case engine against itself. Drift remediation, scaling events, CVE response, service upgrades, incident response, and compliance remediation are all case definitions with worker pipelines. The platform manages its own operations through its own case management model.
- **Extent:** 97 source files. REST API (8 resources, 29 operations via @McpDomain SPI). K8sClientRegistry with proactive credential refresh + 401 retry. K8sWatchManager (4 resource types). PodmanWatchManager. 37 RAS ganglia + 15 situations for health detection. ApplicationEventBroadcaster with SSE ring buffer.

### 6. Canonical Deployment Topologies
5×4 matrix of deployment patterns tested against the YAML frontend: single-service, microservices, multi-tier, sidecar-mesh, event-driven × compilation, reconciliation, drift, fault. 14 YAML exemplars proving the desiredstate framework handles real-world topology patterns.
- **Differentiation:** The topology test suite is itself a proof point — it demonstrates that the same YAML-driven reconciliation model works across diverse deployment patterns without special-casing. No competitor has this breadth of verified topology support.
- **Extent:** Test-only module. 14 YAML exemplars. ReconciliationTestBase + TopologyTestBase + FailableNodeProvisioner test fixtures.

## Cross-Repo Patterns

- **SPI quad** from `casehub-desiredstate` is the spine — all 5 domain modules (deployment, infra, compliance, IoT/deprecated, container) implement the same 5 interfaces
- **ThresholdFaultPolicy** from desiredstate used by 4 of 5 domains (deployment, infra, IoT, container) — consistent tier-3 escalation
- **Approval workflow** spans all domains: ApprovalEvaluator → PlanStore → ApprovalAuthorizer
- **RAS integration**: deployment adaptive topology and app module health monitoring both consume SituationChangeEvent
- **Case model**: the ops console runs the engine's case descriptors for its own operational responses — the platform operates on itself
- **YAML frontend**: ContainerNodeSpecFactoryProvider + InfraWrappingFactory bridge domain node specs to the yaml-core YAML loader, enabling topology YAML files

## Vision

- **CrossplaneNodeProvisioner** (#86) — delegate provisioning to Crossplane providers for multi-cloud
- **casehub-application NodeSpec** (#83) — deploy YAML-first case apps via desiredstate (the platform deploys instances of itself)
- **Live K8s tests with Testcontainers K3s** (#85) — real K8s verification in CI
- **Three-cloud adaptive ops demo** (#91) — self-healing across AWS/GCP/Azure
- **Deployment monitoring example** (#89) — CloudEvent bridge with RAS detection
- **Adaptation conflict detection metrics** (#93) — Micrometer counters for conflicting adaptation rules