# 0008 — Claudony Is Not a Dependency of CaseHub

Date: 2026-04-22
Status: Accepted

## Context and Problem Statement

CaseHub is a blackboard orchestration engine for Quarkus-based AI agents.
Claudony is one deployment environment that implements CaseHub's worker SPIs.
The question is whether any CaseHub module may ever depend on Claudony.

## Decision Drivers

* casehub-core must be embeddable in any Quarkus application without Claudony
* CaseHub defines SPIs (WorkerProvisioner, CaseChannelProvider,
  WorkerContextProvider, WorkerStatusListener) precisely so that implementors
  are interchangeable — Claudony, Docker, Nono, Remote, Human
* Claudony is one provisioner among many and must not be architecturally
  privileged over others

## Considered Options

* **Option A** — No CaseHub module ever depends on Claudony; SPI implementations
  live in Claudony or in a Claudony-owned bridge module
* **Option B** — An optional `casehub-claudony` module in the CaseHub repo
  provides convenience integrations, depending on both CaseHub and Claudony

## Decision Outcome

Chosen option: **Option A**. CaseHub defines interfaces; Claudony implements
them. The integration code lives in Claudony, not in CaseHub. No CaseHub
module — core, mcp, persistence, or otherwise — may import Claudony types.

Any data that CaseHub stores about Claudony (e.g. a worker's session ID) is
typed as a plain `String` — an opaque identifier, not a Claudony type.

### Positive Consequences

* casehub-core is a true library — embeddable without Claudony on the classpath
* All provisioners are equal — no provisioner has a first-class CaseHub module
* CaseHub can be developed and tested independently of Claudony

### Negative Consequences / Tradeoffs

* Claudony-specific conveniences must live in Claudony, not CaseHub

## Links

* Matching ADR in Claudony: `claudony/adr/0005-casehub-integration-is-optional.md`
* [Ecosystem Design](https://github.com/mdproctor/claudony/blob/main/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md)
