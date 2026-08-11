---
layout: post
title: "Why Your Agent Forgets Everything"
date: 2026-08-04
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [agent-memory, architecture, neocortex, eidos]
series: issue-800-agent-learning-memory
---

"Memory" in LLM agents has become one of those terms that means whatever the vendor needs it to mean. Most of the time, it means a chat log with summarisation bolted on. LangChain's memory modules, Mem0, ChatGPT's persistent memory — they all solve the same problem: maintaining conversational coherence across sessions. Store what was *said*, summarise it when it gets long, make it available next time. Any agent can resume where the last one left off, or consume the summary without reading the full transcript.

This kind of memory is real, and it matters. Without it, every agent interaction starts cold. But it answers exactly one question: *what was discussed?* It is, fundamentally, context management for conversation. And conversation is not the only thing agents do.

An agent that executes work — handles a medical triage, processes a financial alert, reviews a pull request — produces something more interesting than a chat transcript. It produces *outcomes*. The triage agent that ran fifty cases has seen which symptom patterns lead to correct escalations and which ones don't. The financial agent has learned that entity resolution works when the input includes transaction metadata but fails without it. The review agent has discovered it catches more issues when it reads tests before implementation.

None of that is in the chat log. The chat log records what the agent said while doing the work. The outcomes — what it did, whether it worked, what patterns emerge across many invocations — live nowhere. Every invocation is a blank slate. The agent that ran fifty successful triages is indistinguishable from one that just started.

That's two fundamentally different memory problems:

**Conversational memory** maintains context within and across dialogues. It answers *"what was said?"* and its core operation is summarisation — compress a growing transcript into something an LLM context window can hold. This is session management. It's well-understood and widely implemented.

**Experiential memory** accumulates knowledge from doing work. It answers *"what was learned?"* and its core operations are structured recording, pattern synthesis, and feedback into future decisions. An agent doesn't need to remember what it said to a patient; it needs to know that it succeeds at triage when lab results are present and fails without them. This isn't context management — it's competence development.

Most agent frameworks stop at conversational memory because most agents are conversational. They chat, they answer questions, they maintain a thread. But agents that execute work within an orchestration engine — agents dispatched to capabilities, routed by scoring, evaluated by outcomes — need experiential memory. The orchestrator already has structured data about what happened: which agent was dispatched, to which capability, with what inputs, producing what result. That structured signal is far richer than anything a chat summary could provide. It's just never been captured as the agent's own memory.

The Smallville paper got this distinction right before most of the industry arrived at the question. Their agents didn't just remember conversations — they accumulated a *memory stream* of timestamped experiences and periodically synthesised higher-level insights through *reflection*. "I've noticed Klaus is avoiding me at parties" isn't a conversation summary. It's a pattern recognised across many individual observations. Emergence World extended this with a triple memory system: episodic, reflective diary, and relationship state. But even these are scoped to a single continuous simulation. No project has agents that carry structured experiential memory across independent task invocations in a production orchestration engine.

## The gap in CaseHub

CaseHub has had case-level memory for months. When a case completes, `CbrCaseRetainObserver` stores the plan trace — which workers ran, what they produced, whether it worked. At the next dispatch, `CbrRetrievalService` pulls similar cases and feeds the data into routing. The system learns from case outcomes. But agents themselves accumulate nothing. The CBR pipeline remembers what happened in *cases*; no pipeline remembers what happened to *agents*.

The landscape analysis I wrote for Wacky Manor identified this as one of three structural gaps across every multi-agent project surveyed. The infrastructure to close it was closer than I expected.

## The storage question that wasn't

The first design question seemed like it should be hard: where does agent memory live? A new store? A specialised agent-memory module?

It turned out the answer was already in the codebase. Neocortex's `CaseMemoryStore` takes an `entityId` and a `domain`. For case memory, entityId is the customer or data subject. For agent memory, entityId is the agentId. The `domain` field — `"experience"`, `"relationship"`, `"reflection"` — discriminates the memory type. Same backends (JPA, Qdrant, Graphiti), same query infrastructure, same GDPR erasure paths. The entire agent memory layer sits *on top of* existing storage with zero new persistence infrastructure.

What I hadn't expected: neocortex already had the agent-level types. `ExperienceEvent` (a sealed hierarchy — Observation, Action, Outcome), `ExperienceStream` for recording, `RelationshipObserver` that auto-fires on agent-to-agent interactions, even `ReflectionService` with a `ReflectionSynthesizer` SPI. All present, all tested, all with no callers from the engine. The bridge was missing, not the foundation.

## Five phases, one loop

The architecture forms a learning loop:

**Experience recording** → **Relationship detection** → **Reflection synthesis** → **Goal evolution** → **Routing feedback**

The engine's `WorkflowExecutionCompletedHandler` is where agents complete work. It already calls `PersonalitySignalRecorder` (JPAF cognitive function reinforcement) and `GoalOutcomeRecorder` (goal decline signals). A new `AgentExperienceRecorder` sits at the same call sites, constructing an `Outcome` event from the worker context — agentId, capability, result, importance — and passing it to neocortex's `ExperienceRecorder` interface.

Relationship detection is already wired. `RelationshipObserver` watches for `ExperienceRecorded` CDI events with a `target-agent` attribute. Once the experience recorder populates that attribute from the case's binding graph, relationships track automatically.

Reflection is where it gets interesting. A configurable hybrid trigger — importance threshold accumulation plus a completion-count ceiling — determines when an agent has enough raw experience to synthesise higher-level insights. The trigger fires asynchronously via the Vert.x event bus; the actual synthesis (an LLM call via `ReflectionSynthesizer`) runs on a virtual thread, off the dispatch critical path. Counters reset on success, survive on failure. The whole mechanism is per-CaseDefinition: agents serving case types that don't declare a `reflection:` block simply don't reflect. Experiences still accumulate — the recording is unconditional — but the synthesis is gated.

Goal evolution consumes reflection output. A `GoalEvolutionObserver` watches for `ReflectionRecorded` events, extracts goal candidates via a `GoalExtractor`, and stores discovered goals in a new `GoalLifecycleStore` in eidos. Declared goals on the descriptor stay immutable — they're identity. Discovered goals live in a separate store, merged at query time. This mirrors the JPAF pattern exactly: base personality profile on the descriptor, learned activation signals in `DispositionSignalStore`, effective weights computed at probe time.

## The SPI extraction

The one piece of real code that shipped was extracting `ExperienceRecorder` and `ReflectionOrchestrator` interfaces from the existing neocortex classes. The engine needs to depend on memory-api (interfaces), not memory (implementations) — same dependency direction as `CaseMemoryStore` and `DispositionSignalStore`. `ExperienceStream implements ExperienceRecorder`. `ReflectionService implements ReflectionOrchestrator`. The signature change — adding `maxSourceMemories` to `reflect()` — was the only breaking change, and the only callers were tests.

The interesting constraint: IntelliJ MCP tools route `project_path` differently for cross-module vs per-file operations. `ide_find_references` needs the parent workspace path to search across modules; `ide_edit_member` needs the submodule path. Using the submodule path for references silently returns "project not found" even when the submodule is indexed. That one cost me about fifteen minutes before the pattern clicked.

## What this opens up

The architecture is designed but only the first implementation task is complete. Four more tasks wire the engine side: `ReflectionConfig` on `CaseDefinition`, the `AgentExperienceRecorder` bridge, the reflection trigger mechanism, and personality transition CBR cases on JPAF evolution.

The part I'm most curious about is what happens when the reflection loop runs for real. Smallville's agents produced surprisingly coherent higher-level insights from raw memory streams — "I've noticed Klaus is avoiding me at parties" emerging from a sequence of low-level spatial observations. Whether CaseHub agents operating in a more structured task environment (capability bindings, plan traces, typed outcomes) produce comparably interesting reflections is an open question. The raw material is more structured but less narrative. My suspicion is the reflections will be more operationally useful — "I succeed at triage when the input has lab results but fail without them" — and less socially interesting. Which is fine. These aren't Smallville villagers; they're platform workers. Operational self-knowledge is exactly what the routing pipeline can use.
