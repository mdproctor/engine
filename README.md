# Case Hub Engine

[![Open PRs](https://img.shields.io/github/issues-pr/casehubio/engine)](https://github.com/casehubio/engine/pulls)

**Case Hub** is a coordination and observability engine for managing complex, goal-driven work where execution cannot be defined by a fixed control flow.

The central concept is the **Case** — a logical unit that brings together shared state (**CaseContext**), events, goals, milestones, and autonomous participants (**Workers**). Unlike workflow engines, Case Hub does not prescribe a sequence of steps. Instead, workers observe state changes and react independently based on their own capabilities and dispatch rules.

---

## Core Concepts

| Concept | Description |
|---|---|
| **Case** | Bounded unit of work with a unique ID, shared context, and goals |
| **CaseContext** | Shared, observable key-value state — the single source of truth |
| **Worker** | Autonomous participant (service, agent, function) that reads/writes context |
| **Capability** | Declared input/output contract of a worker |
| **DispatchRule** | Rule that triggers a worker when a context condition is met |
| **Goal** | Desired state condition; evaluated against CaseContext |
| **Milestone** | Observable progress marker derived from context changes |
| **Reactor** | Core engine: validates events, updates context, emits change notifications |

The Reactor does **not** execute tasks or make decisions — it records facts and notifies participants.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                    Case Hub                       │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │                  Reactor                     │ │
│  │  receive event → update CaseContext →        │ │
│  │  emit ContextChanged → evaluate rules →      │ │
│  │  schedule workers → check goals/milestones   │ │
│  └─────────────────────────────────────────────┘ │
│                                                   │
│   Workers act autonomously on context changes     │
│   (humans · services · workflows · AI agents)     │
└──────────────────────────────────────────────────┘
```

Built with **Quarkus**, uses **JQ expressions** for conditions (for now), and supports **Java Lambda**, **Serverless Workflow DSL** and **LangChain4j** for worker implementations.

---

## Quick Start

### 1. Define a Case in Java

Extend `CaseHub` and describe your case declaratively:

```java
@ApplicationScoped
public class DocumentProcessingCase extends CaseHub {

    @Override
    public CaseHubDefinition getDefinition() {

        Capability processCap = Capability.builder()
                .name("processDocument")
                .inputSchema("{ documentId: .documentId, status: .status }")
                .outputSchema("{ processedDocument: ., status: .status }")
                .build();

        Goal doneGoal = Goal.builder()
                .name("documentProcessingComplete")
                .condition(".status == \"processed\"")
                .kind(GoalKind.SUCCESS)
                .build();

        return CaseHubDefinition.builder()
                .namespace("demo")
                .name("Document Processing")
                .version("1.0.0")
                // Worker: implements the capability as a plain function
                .workers(Worker.builder()
                        .name("document-processor")
                        .capabilities(processCap)
                        .function(ctx -> Map.of(
                                "processedDocument", Map.of("id", ctx.get("documentId")),
                                "status", "processed"
                        ))
                        .build())
                // Rule: trigger worker when context matches condition
                .rules(DispatchRule.builder()
                        .name("trigger-on-processing")
                        .capability(processCap)
                        .on(new ContextChangeTrigger(".status == \"processing\""))
                        .build())
                // Milestone: observable progress marker
                .milestones(Milestone.builder()
                        .name("documentProcessed")
                        .condition(".status == \"processed\"")
                        .build())
                .goals(doneGoal)
                // Case completes when all goals are met
                .completion(GoalExpression.allOf(doneGoal))
                .build();
    }
}
```

### 2. Start a Case

```java
@Inject
DocumentProcessingCase myCase;

// Start with initial context — rule fires immediately because status == "processing"
UUID caseId = myCase.startCase(Map.of(
        "documentId", "doc-123",
        "status",     "processing"
)).toCompletableFuture().join();
```

### 3. Send a Signal (external event into a running case)

Cases can wait for external input. Use `signal()` to write a value into the CaseContext at runtime:

```java
// Case starts without "payment" — no worker fires yet
UUID caseId = myCase.startCase(Map.of("orderId", "order-1"))
        .toCompletableFuture().join();

// Later, an external event arrives — this triggers the dispatch rule
myCase.signal(caseId, "payment", Map.of("amount", 500, "currency", "USD"));
```

### 4. Query the CaseContext

```java
String status = myCase.query(caseId, "status", String.class)
        .toCompletableFuture().join();
```

---

## YAML Definition

Cases can also be described in YAML instead of Java code:

```yaml
dsl: "0.1"
version: "1.0.0"
name: Document Processing Test
namespace: demo
spec:
  capabilities:
    - name: processDocument
      inputSchema: "{ documentId: .documentId, status: .status }"
      outputSchema: "{ processedDocument: ., status: .status }"
  workers:
    - name: document-processor
      capabilities: [processDocument]
      do:
        - setResult:
            set:
              status: processed
  rules:
    - name: trigger-on-processing-status
      capability: processDocument
      on:
        contextChange:
          filter: '.status == "processing"'
  milestones:
    - name: documentProcessed
      condition: '.status == "processed"'
  goals:
    - name: documentProcessingComplete
      condition: '.status == "processed"'
  completion:
    success:
      allOf: [documentProcessingComplete]
```

---

## AI Agent Pipeline

Workers can be LLM agents (via LangChain4j). The pipeline below fetches a document, analyzes its sentiment, then summarizes it — each step triggered by a context change.

### Define LLM agents with LangChain4j

```java
public record SentimentRequest(String documentId, String content) {}
public record SentimentResult(String sentiment, double confidence, List<String> keywords) {}

@RegisterAiService
@SystemMessage("""
        You are an expert sentiment analysis specialist.
        Respond with a JSON object matching SentimentResult:
          { sentiment: "POSITIVE|NEGATIVE|NEUTRAL", confidence: 0.0-1.0, keywords: [...] }
        """)
public interface SentimentAnalysisAgent {

    @UserMessage("Document ID: {data.documentId}\n\nContent:\n{data.content}\n\nProduce a SentimentResult JSON.")
    SentimentResult analyze(@MemoryId String memoryId, @V("data") SentimentRequest input);
}

public record SummaryRequest(String documentId, String content) {}
public record SummaryResult(String summary, List<String> keyPoints) {}

@RegisterAiService
@SystemMessage("""
        You are a concise document summarizer.
        Respond with a JSON object matching SummaryResult:
          { summary: "1-3 sentences", keyPoints: [...] }
        """)
public interface ContentSummarizerAgent {

    @UserMessage("Document ID: {data.documentId}\n\nContent:\n{data.content}\n\nProduce a SummaryResult JSON.")
    SummaryResult summarize(@MemoryId String memoryId, @V("data") SummaryRequest input);
}
```

### Wire agents into Case Hub workers

```java
// Step 1: HTTP fetch — triggers when step == "submitted"
Worker.builder()
    .name("document-fetcher")
    .capabilities(fetchCap)
    .function(workflow("fetch-document")
        .tasks(get("fetchData", "http://api/fetch/{id}")
            .inputFrom("{ id: .documentId }")
            .outputAs("{ data: ., step: \"fetched\" }"))
        .build())
    .build(),

// Step 2: LLM sentiment analysis — triggers when step == "fetched"
Worker.builder()
    .name("sentiment-analyzer")
    .capabilities(sentimentCap)
    .function(workflow("analyze-sentiment")
        .tasks(agent(sentimentAgent::analyze, SentimentRequest.class)
            .inputFrom("{ documentId: .documentId, content: .data.content }")
            .outputAs("{ sentiment: ., step: \"analyzed\" }"))
        .build())
    .build(),

// Step 3: LLM summarizer — triggers when step == "analyzed"
Worker.builder()
    .name("content-summarizer")
    .capabilities(summaryCap)
    .function(workflow("summarize-content")
        .tasks(agent(summarizerAgent::summarize, SummaryRequest.class)
            .inputFrom("{ documentId: .documentId, content: .data.content }")
            .outputAs("{ summary: ., step: \"summarized\" }"))
        .build())
    .build()
```

Each worker writes back to the CaseContext. The Reactor detects the change and fires the next dispatch rule — no orchestrator needed.

---

## Key Design Properties

- **Choreography by default** — workers react to context changes independently; no central controller
- **Orchestration as an option** — can be implemented as an external worker if needed
- **Idempotent scheduling** — dedup prevents double execution of workers on repeated signals
- **Retry with recovery** — configurable retry policy with crash recovery for failed workers
- **JQ expressions** — all conditions, filters, input/output mappings use JQ syntax (for now)
- **Goals define completion** — a case closes when its goal expression is satisfied (`allOf`, `anyOf`)

---

## Modules

| Module | Description |
|---|---|
| `api` | Public API: `CaseHub`, `CaseHubRuntime`, model classes |
| `engine` | Reactor implementation, worker scheduling, event handling |
| `schema` | JSON Schema / YAML model for declarative case definitions |
| `codegen` | Code generation tooling for the DSL |
