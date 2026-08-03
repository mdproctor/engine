# Case Definition Examples

Reference YAML definitions showing how to use the engine's key features.
Each file is a complete, annotated case definition with inline comments
explaining the concepts.

## Examples

### [document-processing.yaml](document-processing.yaml)

A document processing pipeline: upload → OCR → classify → human review → archive.
Demonstrates workers, bindings, milestones, context-change triggers, and external
events. Start here for the core concepts.

### [worker-rights-example.yaml](worker-rights-example.yaml)

A loan approval case showing the three ACL isolation levels:

| Level | Example worker | How it works |
|-------|---------------|--------------|
| **In-process** | `credit-check-worker` | Sandboxed by `inputSchema`/`outputSchema`. No ACL grants needed — the engine passes only projected data in and out. |
| **External (ephemeral)** | `compliance-checker` | Engine mints a unique identity per dispatch (e.g., `agent:worker-a1b2c3d4-7f3e`). Scoped credential token bound to the specific case. Cannot reference other cases. |
| **External (service account)** | `risk-assessment-agent` | Pre-declared identity (`agent:risk-pool@lending.io`). Same identity reused across cases, but grants are case-scoped and revoked on completion. |

Key concepts covered:
- **`authorization:`** — grant case access to human groups at case creation
- **`permissionIntent:`** — declare what a worker is allowed to do (`read-context`, `signal-case`, `read-event-log`)
- **`serviceAccountId:`** — pre-declared worker identity (must resolve to `ActorType.AGENT`)
- **Structural isolation** — scoped tokens physically prevent cross-case access
- **Fail-closed defaults** — omitting `permissionIntent` grants read-only access, not write

### [agent-worker-example.yaml](agent-worker-example.yaml)

An AI agent worker using OpenAI. Shows the `agent:` block on a worker definition
with model configuration, system prompts, and input/output transformers.

### [agent-ollama-example.yaml](agent-ollama-example.yaml)

Same AI agent pattern using Ollama (local LLM). Shows `baseUrl` override for
OpenAI-compatible servers.
