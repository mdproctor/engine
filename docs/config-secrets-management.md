# Config & Secrets Management

**Status:** Implemented (MVP)  
**Version:** 0.1  
**Related:** Issue #247, Design Spec `docs/superpowers/specs/2026-05-14-config-secrets-design.md`

---

## Overview

CaseHub Engine provides a Config & Secrets management system for secure, structured access to configuration and sensitive data. The system:

- **ConfigManager** - Key-value configuration from Quarkus MicroProfile Config
- **SecretManager** - Structured secrets from system properties, K8s Secrets, Vault (future)
- **Placeholder Resolution** - Secrets and config accessible in YAML via `${$secret.name.property}` and `${$config.key}` placeholders, resolved at deserialization time

---

## Quick Start

### 1. Configure Secrets in application.properties

```properties
# OpenAI secrets
openai.apiKey=sk-proj-your-api-key
openai.organizationId=org-your-org-id

# Anthropic secrets
anthropic.apiKey=sk-ant-your-api-key
anthropic.version=2023-06-01
```

### 2. Use Secrets in YAML

```yaml
dsl: "0.1.0"
namespace: example
name: sentiment-analysis
version: "1.0.0"

spec:
  workers:
    - name: sentiment-analyzer
      capabilities: [analyze-sentiment]
      agent:
        systemPrompt: "Analyze sentiment of the given text"
        inputSchema: "${.context.text}"
        outputSchema: "${.sentiment}"
        model:
          openai:
            apiKey: "${$secret.openai.apiKey}"
            organizationId: "${$secret.openai.organizationId}"
            modelName: "gpt-4o-mini"
```

### 3. Secrets Resolved at Deserialization

Placeholders like `${$secret.openai.apiKey}` are resolved once during YAML deserialization, not at runtime. The actual secret values are substituted when the YAML file is loaded.

---

## Placeholder Syntax

### Basic Usage

Placeholders are resolved once at YAML deserialization time using Jackson deserializer.

```yaml
# Secret property access
apiKey: "${$secret.openai.apiKey}"

# Config property access  
timeout: "${$config.worker.timeout}"

# Nested secret properties
username: "${$secret.database.credentials.username}"
password: "${$secret.database.credentials.password}"
```

### Configuration Structure

Properties are grouped by prefix:

```properties
# openai.* becomes $secret.openai.*
openai.apiKey=sk-...
openai.organizationId=org-...

# database.* becomes $secret.database.*
database.credentials.username=admin
database.credentials.password=secret
database.connection.host=localhost
database.connection.port=5432
```

Nested structure:

```json
{
  "openai": {
    "apiKey": "sk-...",
    "organizationId": "org-..."
  },
  "database": {
    "credentials": {
      "username": "admin",
      "password": "secret"
    },
    "connection": {
      "host": "localhost",
      "port": "5432"
    }
  }
}
```

## Agent Provider Secret Conventions

Each LLM provider follows a naming convention for secrets:

| Provider | Secret name | Fields | Notes |
|----------|-------------|--------|-------|
| OpenAI | `openai` | `apiKey`, `organizationId` | `organizationId` is optional |
| Anthropic | `anthropic` | `apiKey` | |
| Mistral AI | `mistralai` | `apiKey` | |
| Google AI Gemini | `googleai` | `apiKey` | |
| Ollama | — | N/A | Local deployment, no authentication required |

**Example: multi-provider case definition**

```yaml
use:
  secrets:
    - openai
    - anthropic

spec:
  workers:
    - name: primary-analyzer
      agent:
        model:
          openai:
            apiKey: "${$secret.openai.apiKey}"
            modelName: "gpt-4"
    - name: fallback-analyzer
      agent:
        model:
          anthropic:
            apiKey: "${$secret.anthropic.apiKey}"
            modelName: "claude-3-sonnet-20240229"
```

**Numeric coercion:** Fields sourced from configMaps that expect numeric types require
`| tonumber` in the JQ expression:

```yaml
temperature: "${$config.\"model-params\".temperature | tonumber}"
maxTokens: "${$config.\"model-params\".maxTokens | tonumber}"
```

---

## Programmatic Access

### ConfigManager

```java
import io.casehub.engine.internal.config.ConfigContext;
import jakarta.inject.Inject;

@ApplicationScoped
public class MyService {
  
  @Inject
  ConfigContext configContext;
  
  public void doSomething() {
    // Get config value
    Optional<Integer> timeout = configContext.configManager()
        .config("casehub.timeout", Integer.class);
    
    // Multi-value config
    Collection<String> items = configContext.configManager()
        .multiConfig("casehub.items", String.class);
  }
}
```

### SecretManager

```java
@ApplicationScoped
public class MyService {
  
  @Inject
  ConfigContext configContext;
  
  public void doSomething() {
    // Get secret
    Map<String, Object> openaiSecret = configContext.secretManager()
        .secret("openai");
    
    String apiKey = (String) openaiSecret.get("apiKey");
    String orgId = (String) openaiSecret.get("organizationId");
  }
}
```

---

## Security Best Practices

### 1. Never Log Secret Values

```java
// ❌ BAD
log.info("API Key: " + apiKey);

// ✅ GOOD
log.info("API Key configured");
```

### 2. Use Environment Variables in Production

```bash
# Don't hardcode in application.properties
export OPENAI_API_KEY=sk-...

# Quarkus Config automatically reads env vars
```

---

## Troubleshooting

### Secret Not Found

**Error:**
```
io.casehub.engine.internal.config.SecretNotFoundException: Secret not found: openai
```

**Solution:**
- Verify properties are configured: `openai.apiKey=...`
- Check property prefix matches secret name
- Ensure properties are available (system props, env vars, application.properties)

### Placeholder Not Resolving

**Issue:** `${$secret.openai.apiKey}` appears as literal string in output

**Solution:**
- Placeholders are resolved at YAML deserialization time
- Verify ObjectMapper is injected (not manually created)
- Check ConfigSecretResolvingDeserializer is registered in ObjectMapper

---

## Migration Guide

### From Hardcoded Secrets

**Before:**
```yaml
agent:
  model:
    openai:
      apiKey: "sk-hardcoded-key"
```

**After:**
```properties
# application.properties
openai.apiKey=sk-your-actual-key
```

```yaml
agent:
  model:
    openai:
      apiKey: "${$secret.openai.apiKey}"
```

### From System.getenv() Calls

**Before:**
```java
String apiKey = System.getenv("OPENAI_API_KEY");
```

**After:**
```java
@Inject ConfigContext configContext;

String apiKey = configContext.configManager()
    .config("openai.apiKey", String.class)
    .orElse(null);
```

---

## Future Enhancements

### Kubernetes Secrets Integration

**Status:** Planned (Issue TBD)

```properties
casehub.secrets.provider=kubernetes
casehub.secrets.kubernetes.namespace=casehub-prod
```

### HashiCorp Vault Integration

**Status:** Planned (Issue TBD)

```properties
casehub.secrets.provider=vault
casehub.secrets.vault.address=https://vault.example.com
casehub.secrets.vault.token=${VAULT_TOKEN}
```

### Secret Declaration in YAML

**Status:** Planned (Issue TBD)

```yaml
use:
  secrets:
    - openai
    - anthropic
```

Enables fail-fast validation and security auditing.

---

## References

- **Design Spec:** `docs/superpowers/specs/2026-05-14-config-secrets-design.md`
- **Implementation Plan:** `docs/superpowers/plans/2026-05-14-config-secrets-implementation.md`
- **Issue #247:** SecretManager SPI for K8s/Vault integration
- **Serverless Workflow:** Inspiration for ConfigManager/SecretManager pattern
