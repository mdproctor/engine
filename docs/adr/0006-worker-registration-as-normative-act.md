# 0006 — Worker Registration is a Normative Act

Date: 2026-04-27
Status: Accepted

## Context and Problem Statement

CaseHub needs a worker discovery and registration system. Workers can enter
the system via three distinct paths:

1. **Static** — declared upfront in a `CaseDefinition`
2. **Provisioned** — spun up on demand by `WorkerProvisioner` when no capable
   worker is available
3. **Self-registering / observed** — already running independently; announces
   itself or is introduced by an existing, trusted participant

The third path introduced a design question: how do we model introduction-by-another
and its implications for trust? A self-announced worker and one introduced by a
long-trusted provisioner are not equivalent. The lineage of discovery matters —
not just *that* a worker joined, but *how it came to be known*.

Concurrently, the Qhorus project has defined a four-layer normative framework
for inter-agent communication:

- **L1 — Speech act theory** (Searle): illocutionary type — directive, assertive,
  commissive, declarative, perlocutionary
- **L2 — Deontic logic**: obligations and permissions created or discharged;
  defeasibility conditions
- **L3 — Social commitment semantics**: commitment operations — create, discharge,
  delegate, cancel
- **L4 — Temporal**: deadline enforcement; what happens when obligations expire

This framework is already recorded in the `MessageLedgerEntry` infrastructure
(quarkus-ledger), with `causedByEntryId` chains representing causal obligation
lineage. The question is whether worker registration belongs inside this
framework or alongside it as a parallel, ad-hoc mechanism.

## Decision

**Worker registration is a normative act.** It is not a technical side-effect
to be logged separately — it is a declarative speech act that constitutively
creates a new participant in the system, with deontic consequences.

The four-layer framework applies:

| Layer | Application to worker registration |
|---|---|
| **Speech act** | Registration is a *declarative* act: saying "I am a participant" makes it so. Introduction-by-another is also declarative, with the introducer as the actor. |
| **Deontic** | Registration creates obligations: the engine is obligated to consider the worker for capable work; the worker is obligated to accept work within its declared capabilities or decline with reason. |
| **Social commitment** | The introduction chain is a commitment chain: `C(introducer → engine, "this worker is trustworthy for these capabilities")`. The introducer's deontic standing transfers as a prior over the introduced worker's initial standing. |
| **Temporal** | Registrations can expire or be revoked; the temporal layer governs the obligation window. |

**Trust derives from discovery lineage**, not from assertion. A worker's initial
deontic standing is a function of how it came to be known:

- Statically declared → highest standing (baked in by the system owner)
- Provisioned by a trusted `WorkerProvisioner` → standing inherits from provisioner's trust level
- Self-announced → lowest initial standing (no voucher)
- Introduced by an existing participant → standing derived from the introducer's chain

This is a web-of-trust applied to agent discovery, mirroring how `causedByEntryId`
chains represent causal obligation lineage in the Qhorus ledger.

**Discovery events are recorded in the normative ledger** using the same
`MessageLedgerEntry` infrastructure (or its CaseHub equivalent), with the
`causedByEntryId` field linking an introduced worker's registration entry to the
registration entry of the participant that introduced it. The discovery chain is
permanently traversable.

## Decision Drivers

- **Ecosystem coherence**: the more the normative framework is applied consistently
  across CaseHub and Qhorus, the more it asserts its value. An ad-hoc trust mechanism
  alongside a principled normative framework would be a missed opportunity and a
  source of conceptual confusion.
- **Lineage completeness**: the ledger already records *what* agents committed to
  and *how work was produced*. Recording *how workers came to be known* completes
  the audit picture and makes trust verifiable rather than implicit.
- **Heterogeneous runtimes**: CaseHub targets heterogeneous worker runtimes (internal
  Quartz workers, Claudony-managed Claude sessions, Docker containers, Nono sandboxes,
  human-in-the-loop). A unified normative model prevents each runtime from needing its
  own ad-hoc trust mechanism.
- **Infrastructure reuse over proliferation**: quarkus-ledger already provides
  tamper-evident, causally-chained ledger entries. Reusing this infrastructure for
  worker registration avoids a second ledger system.

## Consequences

### Positive

- A single normative framework governs both inter-agent communication (Qhorus) and
  worker participation (CaseHub); developers reason about one model, not two.
- Discovery lineage is permanently auditable — who introduced whom, and who introduced
  the introducer, is always traceable.
- Trust is derivable (from the chain) rather than asserted (by the worker itself),
  which is structurally harder to spoof.
- Different entry paths (static / provisioned / self-registered / introduced) are
  unified at the normative level even if their technical mechanisms differ.
- The normative framework gains compound value: each additional domain it governs
  strengthens the argument for it as ecosystem-wide infrastructure.
- The enforcement layer (L4, Drools-based) can be applied to worker registration
  obligations in the same pipeline as message obligations — no separate enforcement
  path needed.

### Constraints this creates

- Worker registration events must produce ledger entries — registration cannot be
  a purely in-memory operation.
- The engine must track `WorkerProvisioner` identity so it can be recorded as the
  `actorId` on provisioned worker registration entries.
- A worker that introduces another must itself be a registered participant with
  sufficient deontic standing to vouch — this must be validated before the
  introduction is accepted.
- Trust level must be a queryable property on the worker's ledger entry, so
  the enforcement layer can use it for work assignment decisions.

## Out of Scope for This ADR

- The specific `LedgerEntryType` or `messageType` values used for worker
  registration events (implementation detail).
- The exact trust scoring function mapping discovery chain depth to deontic
  standing (to be designed when enforcement is implemented).
- The Drools rules governing what a worker at each trust level may and may not
  do (L4, deferred to enforcement phase).

## Links

- ADR-0005 — Worker Provisioner SPIs in `api/spi/` (the SPI contracts this ADR
  governs normatively)
- quarkus-qhorus spec `2026-04-23-message-type-redesign-design.md` — four-layer
  normative framework definition
- quarkus-qhorus spec `2026-04-26-normative-ledger-design.md` — ledger infrastructure
  this decision extends
- Ecosystem design `2026-04-13-quarkus-ai-ecosystem-design.md` — worker provisioner
  SPI overview and deployment topologies
