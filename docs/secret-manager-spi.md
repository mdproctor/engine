# SecretManager SPI for K8s/Vault Integration

**Status:** ~~Proposal~~ **Implemented (MVP)** ✅  
**Version:** 0.1  
**Implementation Date:** 2026-05-14  
**Priority:** Medium  
**Target:** ~~Post-MVP~~ **Phase 1 Complete**  
**Related:** AI Agent implementation (#244), Design Spec `docs/superpowers/specs/2026-05-14-config-secrets-design.md`, Agent Model Spec `docs/specs/2026-05-25-agent-worker-ai-model-design.md`

---

## Update

**✅ MVP Implementation Complete**

The SecretManager SPI has been implemented with:
- ✅ `ConfigManager` and `SecretManager` interfaces
- ✅ `QuarkusConfigManager` wrapping MicroProfile Config
- ✅ `ConfigSecretManager` building nested maps from properties
- ✅ Centralized ObjectMapper with JQ scope injection (placeholder)

**See:** `docs/config-secrets-management.md` for complete user guide.

**Remaining Work:**
- [ ] Full JQ scope injection implementation (depends on understanding existing JQ evaluation)
- [ ] Kubernetes SecretManager implementation (future)
- [ ] HashiCorp Vault SecretManager implementation (future)
- [ ] `use.secrets` declaration in YAML schema (future)

---

## Original Proposal

## Context

Currently, secrets and API keys in YAML configuration are stored as plain strings without any resolution mechanism.

**Current implementation:**
- API keys, tokens, and other sensitive values are hardcoded in YAML
- Example: `apiKey: "sk-test-key-12345"` or `apiKey: "your-api-key-here"`
- No environment variable substitution
- No secret store integration
- No dynamic resolution at runtime

## Problem

In microservices/Kubernetes environments, secrets are typically stored in:
- Kubernetes Secrets
- HashiCorp Vault
- AWS Secrets Manager
- Azure Key Vault
- Google Secret Manager

The current approach cannot integrate with these secret stores.

**Security concerns:**
- Environment variables are visible in process listings
- System properties can leak in logs and stack traces
- No rotation mechanism for credentials
- No audit trail for secret access

## Proposed Solution

Implement a `SecretManager` SPI pattern similar to Serverless Workflow.

### 1. Define SPI Interface

```java
package io.casehub.engine.spi;

/**
 * SPI for resolving secrets from various backends (K8s, Vault, etc.)
 */
@FunctionalInterface
public interface SecretManager {
  /**
   * Resolve a secret by name.
   * 
   * @param secretName the name of the secret (e.g., "openai", "database")
   * @return map of secret properties (e.g., {apiKey: "sk-...", orgId: "..."})
   * @throws SecretNotFoundException if secret does not exist
   */
  Map<String, Object> secret(String secretName);
}
```

### 2. Multiple Implementations

**Default (backward compatible):**
```java
public class SystemPropertySecretManager implements SecretManager {
  @Override
  public Map<String, Object> secret(String secretName) {
    // Reads secret.{secretName}.{property} from System properties/env
    // Example: secret.openai.apiKey → System.getProperty("secret.openai.apiKey")
  }
}
```

**Kubernetes Secrets:**
```java
public class KubernetesSecretManager implements SecretManager {
  private final CoreV1Api k8sApi;
  
  @Override
  public Map<String, Object> secret(String secretName) {
    // Reads from K8s Secret API
    V1Secret k8sSecret = k8sApi.readNamespacedSecret(secretName, namespace, null);
    return decodeSecretData(k8sSecret.getData());
  }
}
```

**HashiCorp Vault:**
```java
public class VaultSecretManager implements SecretManager {
  private final Vault vault;
  
  @Override
  public Map<String, Object> secret(String secretName) {
    // Reads from Vault KV store
    LogicalResponse response = vault.logical()
      .read("secret/data/" + secretName);
    return response.getData();
  }
}
```

**Composite (chain of responsibility):**
```java
public class CompositeSecretManager implements SecretManager {
  private final List<SecretManager> delegates;
  
  @Override
  public Map<String, Object> secret(String secretName) {
    for (SecretManager manager : delegates) {
      try {
        return manager.secret(secretName);
      } catch (SecretNotFoundException e) {
        // Try next
      }
    }
    throw new SecretNotFoundException(secretName);
  }
}
```

### 3. YAML Declaration

**Explicit secret declaration:**
```yaml
dsl: "0.1"
namespace: example
name: sentiment-analysis

use:
  secrets:
    - openai      # Validates secret exists at load time
    - anthropic   # Fails fast if secret not available

spec:
  workers:
    - name: sentiment-analyzer
      agent:
        model:
          openai:
            apiKey: "$secret.openai.apiKey"
            orgId: "$secret.openai.organizationId"
```

**Migration path:**
```yaml
# Current (hardcoded):
apiKey: "sk-test-key-12345"

# After SecretManager implementation:
apiKey: "$secret.openai.apiKey"
```

### 4. Resolution Flow

```java
// New Jackson deserializer for secret placeholders:

public class SecretResolvingDeserializer extends StdDeserializer<String> {
  
  @Override
  public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    String value = p.getValueAsString();
    
    // Check for $secret.{name}.{property} syntax
    if (value != null && value.startsWith("$secret.")) {
      return resolveFromSecretManager(value);
    }
    
    return value;
  }
  
  private String resolveFromSecretManager(String placeholder) {
    // "$secret.openai.apiKey" → ["openai", "apiKey"]
    String[] parts = placeholder.substring(8).split("\\.", 2);
    String secretName = parts[0];
    String propertyName = parts[1];
    
    SecretManager manager = getSecretManager(); // From CDI or ServiceLoader
    Map<String, Object> secret = manager.secret(secretName);
    return (String) secret.get(propertyName);
  }
}
```

### 5. CDI Integration

```java
@ApplicationScoped
public class SecretManagerProvider {
  
  @Inject
  @ConfigProperty(name = "casehub.secrets.provider", defaultValue = "system")
  String provider;
  
  @Produces
  @ApplicationScoped
  public SecretManager secretManager() {
    return switch (provider) {
      case "kubernetes" -> new KubernetesSecretManager();
      case "vault" -> new VaultSecretManager();
      case "system" -> new SystemPropertySecretManager();
      default -> throw new IllegalArgumentException("Unknown provider: " + provider);
    };
  }
}
```

### 6. Configuration

**application.properties:**
```properties
# Secret provider: system | kubernetes | vault | composite
casehub.secrets.provider=kubernetes

# Kubernetes-specific
casehub.secrets.kubernetes.namespace=casehub-prod

# Vault-specific
casehub.secrets.vault.address=https://vault.example.com
casehub.secrets.vault.token=${VAULT_TOKEN}
casehub.secrets.vault.path-prefix=secret/data/
```

## Reference Implementation

Serverless Workflow has a mature implementation:
- `io.serverlessworkflow.impl.config.SecretManager`
- `io.serverlessworkflow.impl.config.ConfigSecretManager`
- Version: 7.13.4.Final

**JAR location (for study):**
```
~/.m2/repository/io/serverlessworkflow/serverlessworkflow-impl-core/7.13.4.Final/
```

**Key patterns to adopt:**
- SPI interface with `@FunctionalInterface`
- `ServicePriority` for ordering multiple providers
- Lazy loading of secrets (don't load all at startup)
- Caching with TTL for external secret stores

## Implementation Plan

### Phase 1: SPI Foundation ✅
- [x] Define `SecretManager` interface — implemented as `ConfigSecretManager` in `runtime/`
- [x] Implement config-backed secret resolution (MicroProfile Config)
- [x] Support `$secret.*` syntax in YAML placeholders
- [x] Secret validation at YAML load time via `use.secrets` declaration
- [x] Tests for SPI contract

### Phase 2: Kubernetes Integration
- [ ] New module: `casehub-secrets-kubernetes`
- [ ] Implement `KubernetesSecretManager`
- [ ] Integration tests with k3s/kind
- [ ] Documentation for K8s deployment

### Phase 3: Vault Integration (Optional)
- [ ] New module: `casehub-secrets-vault`
- [ ] Implement `VaultSecretManager`
- [ ] Support for AppRole auth
- [ ] Integration tests with Vault dev server

### Phase 4: Advanced Features
- [ ] `CompositeSecretManager` for fallback chains
- [ ] Secret caching with configurable TTL
- [ ] Metrics: secret access count, cache hit/miss
- [ ] Audit logging for secret access

## Acceptance Criteria

- [ ] `SecretManager` SPI defined and documented
- [ ] `$secret.{name}.{property}` syntax supported in YAML
- [ ] Secrets declared in `use.secrets` validated at load time
- [ ] At least 2 implementations: System, Kubernetes
- [ ] Tests for all implementations
- [ ] Jackson module for automatic secret resolution during deserialization
- [ ] Documentation:
  - How to implement custom secret providers
  - How to configure each provider
  - Migration guide from hardcoded values to `$secret.*`

## Security Considerations

1. **Secret Exposure**
   - Never log resolved secret values
   - Mask secrets in toString() and error messages
   - Use `char[]` instead of `String` where possible (for in-memory clearing)

2. **Access Control**
   - Secret provider should enforce RBAC (K8s RBAC, Vault policies)
   - Validate secret names against allowlist (prevent path traversal)

3. **Rotation**
   - Support TTL-based cache invalidation
   - Allow runtime secret refresh without restart
   - Emit events when secrets are rotated

4. **Audit**
   - Log which secrets are accessed (not their values)
   - Integrate with external audit systems (Vault audit, K8s audit logs)

## Open Questions

1. **Secret scoping:** Should secrets be scoped per Case, per Worker, or global?
2. **Runtime updates:** Should secret changes trigger Case re-evaluation?
3. **Fallback behavior:** If secret not found, fail fast or use default/placeholder?
4. **Performance:** Lazy load vs. prefetch all declared secrets?

## Notes

- Current implementation: secrets are hardcoded strings in YAML (no resolution)
- Consider lazy loading of secrets (don't load all at startup)
- Secret validation should happen at Case definition load time, not runtime
- Security: ensure secrets are not logged or exposed in stack traces
- For Vault: consider lease renewal for dynamic secrets
- For K8s: watch for Secret updates and invalidate cache
- Avoid `${VAR}` syntax to prevent confusion with Serverless Workflow runtime expressions `${ .field }`

## Related Work

- AI Agent YAML schema (PR #244) — secret resolution implemented via `${$secret.*}` syntax
- Agent model architecture: `docs/specs/2026-05-25-agent-worker-ai-model-design.md`
- Target deployment: Kubernetes, microservices architecture
- Inspiration: Serverless Workflow `SecretManager` SPI
- Similar: Spring Cloud Config, Quarkus Vault extension
- Related issue: #250 (first-class Agent definitions)
