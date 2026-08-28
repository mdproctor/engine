---
layout: post
title: "From Human Tasks to Governed Yield"
date: 2026-08-26
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [architecture, judgment, oversight, sealed-types]
series: issue-994-governed-yield
---

# From Human Tasks to Governed Yield

CaseHub's engine has always needed a way to pause execution and wait for a judgment — a dosage review, an ethics approval, a compliance sign-off. Until now, that mechanism was `HumanTaskTarget`: a binding target that dispatched work items to human reviewers via the work inbox.

The name tells you the problem. It assumes the reviewer is human.

When we started wiring LLM-backed review into case definitions, the assumption broke immediately. An AI analyst evaluating a risk assessment doesn't fit `HumanTaskTarget`. Neither does a remote A2A agent performing a compliance check. Both are callers making judgments — but neither is a human task.

The deeper issue isn't the name. It's the type structure. `HumanTaskTarget` couples the *mechanism* (pause, yield, await response) to the *caller type* (human). Every field on the target — candidate groups, claim deadlines, work item templates — assumes an inbox-based human workflow. There's no room for an LLM's model configuration, an A2A agent's endpoint, or a trust score that gates whether the response is accepted at all.

## What JudgmentTarget changes

`JudgmentTarget` decouples the yield mechanism from the caller. The target owns what's shared across all caller types: the prompt describing what judgment is needed, input/output mappings, expiration, and — critically — verification and evidence requirements. The caller-specific configuration moves into a sealed `CallerConfig` hierarchy:

```java
public sealed interface CallerConfig
    permits CallerConfig.Human, CallerConfig.Llm,
            CallerConfig.A2A, CallerConfig.Any {

  record Human(CandidateSetSpec candidateGroups,
               String title, Set<String> outcomes,
               String templateRef, ...) implements CallerConfig {}

  record Llm(String model, String modelName,
             String systemPrompt) implements CallerConfig {}

  record A2A(String endpoint, String skill,
             boolean streaming) implements CallerConfig {}

  record Any() implements CallerConfig {}
}
```

`CallerConfig.Human` carries everything `HumanTaskTarget` used to carry — candidate groups, titles, outcomes, claim deadlines. Nothing is lost. But now a case definition can declare `JudgmentTarget.forLlm()` with model configuration, or `JudgmentTarget.forA2A()` with an endpoint, or `JudgmentTarget.forAny()` when the caller type shouldn't matter.

## The verification layer

The real unlock isn't the type polymorphism — it's what the unified type makes possible. Because every caller routes through the same `JudgmentTarget`, the engine can apply a verification pipeline to every response, regardless of who provided it.

`JudgmentVerifier` evaluates the response after it arrives. Did the caller provide the required evidence? Is their trust score high enough? Does the response meet the schema constraints? `EvidencePresenceVerifier` is the first built-in strategy — it checks that all required `EvidenceRequirement` entries are present in the response.

When verification fails, `JudgmentEscalator` decides what happens: re-yield to the same caller with feedback, escalate to a different caller type, or fault the case. The default escalator re-yields on insufficient evidence (up to a configurable attempt limit) and faults on outright rejection.

This pipeline doesn't exist in the `HumanTaskTarget` world. Human reviewers were trusted unconditionally — the engine dispatched the work item and accepted whatever came back. With governed yield, trust is earned and verified, not assumed.

## The migration

I deleted `HumanTaskTarget` entirely — no deprecation period. `humanTask:` YAML blocks now produce `JudgmentTarget.forHuman()` transparently, so existing case definitions continue to work. The sealed `BindingTarget` hierarchy lost one permit and gained one. The `HumanTaskScheduler` SPI was replaced by `JudgmentScheduler`, which handles all caller types through the sealed `JudgmentPayload`.

The interesting part of the migration wasn't the mechanical refactoring. It was seeing how many places in the engine had hardcoded assumptions about human callers — switch cases that matched `HumanTaskTarget` and did nothing for other types, helper methods that extracted `title()` and `candidateGroups()` directly from the target. Each of those sites was a place where a non-human caller would have been silently ignored.

That's the real argument for the unified type. It's not about elegance. It's that exhaustive pattern matching on a sealed hierarchy makes the compiler catch every site where a new caller type would be invisible.
