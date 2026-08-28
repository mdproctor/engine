---
layout: post
title: "Judgment in the Loop"
date: 2026-08-28
entry_type: note
subtype: diary
projects: [casehubio/engine, casehubio/blocks]
tags: [architecture, judgment, patterns, execution-model, complex-systems]
series: issue-994-governed-yield
---

# Judgment in the Loop

*Continues from [From Human Tasks to Governed Yield](2026-08-26-mdp01-from-human-tasks-to-governed-yield.md).*

The previous entry covered the engine side — replacing `HumanTaskTarget` with `JudgmentTarget`, unifying the scheduler SPI, wiring the verification pipeline. That work handles judgment at the case level: a binding fires, the engine yields, a caller responds, the response is verified. Clean and well-scoped.

But the engine isn't the only thing that orchestrates work.

## The gap in the pattern layer

Blocks' agentic patterns — SUPERVISOR, DEBATE, PIPELINE — run their own orchestration loops inside a single worker function invocation. A SUPERVISOR dispatches agents, aggregates results, evaluates termination, and iterates. All within `AbstractExecutionDriver.executeIteration()`, a five-phase loop: route, activate, dispatch, aggregate, terminate.

None of those phases involves judgment. The supervisor's "review" is just another agent invocation — no evidence requirements, no verification, no audit trail. The debate judge's convergence decision vanishes into a `TerminationDecision.Complete` return value. A PIPELINE step completes and the next one fires, with nothing in between to ask "was that output actually good enough?"

This matters more than it might seem. In complex adaptive systems, the boundary between execution and evaluation is where most failure modes live. A system that executes without evaluating is operating in open loop — it can't correct course based on the quality of its own outputs. The judgment phase closes that loop.

## Closing the loop with JudgmentPhase

The question was where to put it. Three options surfaced:

**Wrap the termination condition** — a `JudgmentAwareTermination` that calls the LLM before delegating to the inner termination. This shoehorns judgment semantics into termination semantics. "Should we stop?" is a different question from "was this output acceptable?" Conflating them makes the model harder to reason about.

**Add a listener callback** — listeners observe execution state without influencing it. Their methods return void. A judgment that can't reject the output and force re-iteration isn't a judgment — it's a comment.

**Add a first-class phase** — `ExecutionModel` gains a 12th component: `@Nullable JudgmentPhase<T>`. The driver calls it between aggregation and termination. It returns a sealed `JudgmentDecision`: Approved, Rejected, or Escalated. Approved clears the feedback buffer and falls through to termination. Rejected stores feedback for the next iteration and short-circuits back to the top of the loop. Escalated aborts.

I went with the third. Judgment is a concern at the same level as routing, activation, aggregation, and termination. It belongs in the model, not wrapped around another concern.

## Re-iteration as error correction

The interesting design choice is what happens on rejection. The obvious approach is to fail the pattern — the judgment said "not good enough," so we stop. But that's open-loop thinking again. In a closed-loop system, rejection is feedback, not failure.

When `JudgmentDecision.Rejected` fires, the driver stores the rejection feedback and returns `null` to the loop — which means "continue." The next iteration dispatches agents again. This time, the `JudgmentContext` carries `previousFeedback` from the prior rejection. The prompt builder includes it:

```
Previous feedback (address this):
Missing error handling analysis.

Work output:
[new agent output from re-execution]

Iteration: 2
```

The agents get another chance, informed by what was wrong. Bounded by `MaxIterationsTermination` — no infinite loops. But within those bounds, the system self-corrects.

This is the same pattern as control theory's negative feedback loop: measure the output, compare to the desired state, feed the error back as input. Except here the "controller" is an LLM reading structured feedback, and the "plant" is a set of AI agents executing tasks. The loop dynamics are less predictable than a PID controller — but the architectural shape is identical.

## Defaults that disappear

Each pattern type has a natural judgment placement. SUPERVISOR's review IS the judgment. DEBATE's judge IS the judgment. PIPELINE's quality gate IS the judgment. So the default `mode` per pattern type does the right thing without configuration:

```yaml
pattern:
  type: SUPERVISOR
  judgment:
    prompt: "Review the analysis output"
    caller:
      type: llm
    verifier: schema-validation
```

No `mode:` field needed. The SUPERVISOR's judgment replaces its existing review mechanism — the judgment caller IS the supervisor. For PIPELINE, judgment runs after each step by default. For DEBATE, judgment replaces the judge's convergence decision.

Override with `mode: post-step` when you want something different — say, a SUPERVISOR where agents execute, the supervisor reviews, and then a separate LLM validates the supervisor's review. Two levels of evaluation. The config surface only grows when you need it to.

## What this opens up

The governed yield epic is done — engine, blocks, and qhorus all wired. The immediate value is auditability: every judgment call in a pattern now produces evidence, goes through verification, and gets recorded. A SUPERVISOR's review of an AML transaction analysis isn't just "the agent said it was fine" — it's a structured decision with reasoning evidence, verified against a schema, traceable to a specific caller identity.

The deeper value is composability. Judgment is now a plug-in phase, not a hardcoded mechanism. A domain can write its own `JudgmentPhase` that applies domain-specific evaluation logic — clinical review criteria, regulatory compliance checks, safety assessments — and wire it into any pattern type. The pattern doesn't change. The judgment does.

What I haven't built yet: human callers in patterns. LLM and A2A callers work inline — synchronous call within the loop. Human callers need the pattern to yield mid-execution and resume later, which is a new `WorkerFunction` lifecycle variant. The `DagNode.judgment` field (engine#1000) carries the metadata for that future — but the execution model for mid-pattern yield is still ahead.
