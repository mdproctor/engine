---
title: "Three Ways a Quarkus Extension Can Silently Do Nothing"
date: 2026-08-17
author: Mark Proctor
tags: [casehub, engine, annotations, quarkus, build-extension]
entry_type: note
subtype: diary
status: draft
---

The casehub-engine-annotations module reached Batch 4 today — repeatable `@Bind` fix, goal-to-effect-key mapping, build-time validation. Twelve tasks done across four batches. The annotation scanning pipeline produces CaseDefinition CDI beans from `@Case` interfaces via Jandex and a `@Recorder` pattern. That's the clean summary.

The real story is the three hours I lost to silent failures in the Quarkus build extension infrastructure.

## The Debugging Chain

The first `@BuildStep` method compiled, was listed in `quarkus-build-steps.list`, and produced no output. No error. No warning. The Quarkus app started normally. I added `System.err.println` — nothing. Changed to `LOG.warn` — nothing. Added a deliberate `throw new RuntimeException("IS RUNNING")` — nothing.

The breakthrough came from `QuarkusUnitTest.assertException()`. When the test expected an exception and the build *succeeded*, the assertion failure told me the step never ran. When I got it right, the assertion *caught* the exception — confirming invocation. It's a binary signal where logging gives you nothing.

Three distinct failures stacked on top of each other, each with the same symptom (build step silently ignored):

**1. No build items produced.** A `@BuildStep void validate(CombinedIndexBuildItem)` that only throws on error has no output build items. Quarkus's build graph scheduler treats it as dead code and never schedules it. Fix: `@Produce(ServiceStartBuildItem.class)`.

**2. Annotation type not indexed.** Jandex's `index.getAnnotations()` needs the annotation *type* itself in a Jandex-indexed JAR. I had the annotated class indexed but not the annotation class. The runtime module needed `jandex-maven-plugin`. Error message was clear once it appeared — "Index did not contain annotation definition" — but it only surfaced after fixing problem 1.

**3. No extension metadata.** The runtime JAR needs `META-INF/quarkus-extension.properties` pointing to the deployment artifact. Without it, the deployment module's build steps are discovered but never loaded. This one had zero diagnostic output.

Each fix unmasked the next. None produced a useful error independently.

## What's Working

The annotation model scans `@Case` interfaces at build time, extracts metadata into recordable descriptor records, and produces `CaseDefinition` synthetic CDI beans via a `@Recorder` at `STATIC_INIT`. Worker methods become `WorkerDescriptor` entries; `@Bind` triggers become `BindingDescriptor` entries (repeatable — multiple bindings per method). GOAP mode infers action preconditions from parameter types and effects from return types, with `@Effect` and `@SoftDependency` overrides.

Build-time validation catches trigger exclusivity violations, duplicate names, missing `-parameters` flag, and `@SystemPrompt` conflicts. A `GoalConditionParser` extracts `.keyName != null` patterns from `@Goal` conditions to populate the `goalToEffectKeys` map for GOAP planning.

## What's Next

Four batches remain. The big one is Batch 5: Gizmo synthetic subclass generation and `AnnotationWorkerFunction` — making the default methods on `@Case` interfaces actually executable as worker functions. Without it, the annotation model is metadata-only. After that, a two-phase recorder (RUNTIME_INIT for `ExpressionEngineRegistry` resolution), `@Completion` wiring via default method invocation, and `@Customize`/`@SystemPrompt` processing.

The silent-failure debugging was expensive but the garden entries should save someone else the same three hours.
