# Config & Secrets Management System Design

**Date:** 2026-05-14  
**Status:** Approved  
**Author:** Claude (brainstorming with user)  
**Related Issues:** #247 (SecretManager SPI for K8s/Vault integration)

---

## Executive Summary

This design introduces a Config & Secrets management system for casehub-engine, inspired by Serverless Workflow SDK's ConfigManager/SecretManager architecture. The system provides:

- **ConfigManager** - Key-value configuration access (wraps Quarkus MicroProfile Config)
- **SecretManager** - Structured secret resolution from various backends
- **JQ Integration** - Secrets accessible in YAML via `${$secret.name.property}` expressions
- **Centralized ObjectMapper** - Single CDI bean for all YAML/JSON processing with JQ scope injection
- **Decorator Pattern** - Opt-in audit logging and caching for compliance and performance
- **Adapter Architecture** - Wraps Serverless Workflow classes while maintaining casehub-specific extensions

**Scope:** casehub-engine only (not platform-wide). Internal SPI in `io.casehub.engine.internal.config.*`.

**Resolution Timing:** Runtime evaluation during JQ expression execution, NOT at YAML deserialization.

**MVP Scope:** ConfigManager + SecretManager with system properties/Quarkus Config backend. Future: K8s Secrets, Vault integration.

---

## Motivation & Context

### Current State

**Problems:**
1. No environment variable resolution in YAML - API keys must be hardcoded
2. AI model providers use direct `System.getenv()` calls with no abstraction
3. No secret store integration (K8s Secrets, Vault, AWS Secrets Manager)
4. No audit trail for secret access (EU AI Act/GDPR compliance gap)
5. Manual ObjectMapper creation throughout codebase - no central JQ scope configuration

**Example current code:**
```java
// Direct System.getenv() call
public OpenAiChatModelProvider() {
  this.apiKey = System.getenv("OPENAI_API_KEY");
}
```

```yaml
# Hardcoded secrets in YAML
agent:
  model:
    openai:
      apiKey: "sk-hardcoded-key-12345"  # ❌ Security risk
```

### Inspiration

Serverless Workflow SDK 7.13.4.Final provides mature ConfigManager/SecretManager:
- **ConfigManager** - Programmatic config access from Java code
- **SecretManager** - Structured secrets accessible via JQ `$secret` function
- **Nested map building** - `secret.openai.apiKey` → `{apiKey: "sk-..."}`
- **JQ scope injection** - `childScope.setValue("secret", new FunctionJsonNode(...))`

### Platform Context

CaseHub platform is "compliance-first infrastructure for multi-agent AI systems" targeting EU AI Act and GDPR. Audit logging and secret management are critical.

Current platform docs (PLATFORM.md) do NOT mention config/secrets management - this is a notable gap this design addresses for casehub-engine.

---

## Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Scope** | casehub-engine only | Start focused; can extract to shared module later if needed |
| **Interface location** | `io.casehub.engine.internal.config.*` | Internal SPI like persistence SPIs, not operational SPI like WorkerProvisioner |
| **Implementation** | Wrap & extend Serverless Workflow | Reuse proven logic, add casehub-specific features (audit, caching) |
| **Syntax** | Serverless Workflow only: `${$secret.name.property}` | Consistency with SW, JQ expressions |
| **Resolution timing** | Runtime (JQ evaluation) | Not at deserialization - consistent with SW model |
| **Declaration** | No `use.secrets` in MVP | On-demand resolution; add declaration in future for fail-fast/audit |
| **Managers** | Both ConfigManager + SecretManager | Full SW model - configs for settings, secrets for credentials |
| **ObjectMapper** | Centralized `@ApplicationScoped` bean | Single point for JQ scope injection, marshalling customization |
| **Default ConfigManager** | Wrap Quarkus MicroProfile Config | Standard Quarkus way - includes app.properties, env vars, ConfigSources |
| **Decorators** | AuditingSecretManager, CachedSecretManager | Opt-in via config - compliance and performance |

---

## Architecture Overview & Module Structure

### Module Organization

**Common module** (`casehub-engine-common/src/main/java`):
```
io.casehub.engine.internal.config/
├── ConfigManager.java              (interface)
├── SecretManager.java              (interface)
├── ConfigContext.java              (holder for managers + metadata)
├── SecretNotFoundException.java    (exception)
└── ConfigResolutionException.java  (exception)
```

**Runtime module** (`runtime/src/main/java`):
```
io.casehub.engine.internal.config/
├── impl/
│   ├── QuarkusConfigManager.java          (adapts MicroProfile Config)
│   ├── ConfigSecretManager.java           (adapts SW ConfigSecretManager logic)
│   ├── ServerlessWorkflowAdapter.java     (bridge to SW classes if needed)
│   └── DefaultConfigContext.java          (CDI bean holder)
├── decorator/
│   ├── CachedSecretManager.java           (caching for external stores)
│   └── AuditingSecretManager.java         (compliance logging)

io.casehub.engine.internal.marshaller/
├── CaseHubObjectMapperProducer.java       (@Produces ObjectMapper)
└── jq/
    └── JqScopeInjector.java               (injects $secret/$config functions)
```

### Component Relationships

```
CaseDefinitionYamlMapper
  ↓ injects
@ApplicationScoped ObjectMapper (produced by CaseHubObjectMapperProducer)
  ↓ configures via
JqScopeInjector (injects $secret + $config functions into JQ scope)
  ↓ delegates to
ConfigContext (holds managers)
  ├→ ConfigManager (QuarkusConfigManager)
  └→ SecretManager (ConfigSecretManager → AuditingSecretManager → CachedSecretManager)
       ↓ adapts
ServerlessWorkflow ConfigManager/SecretManager classes
```

### Design Principles

1. **Adapter layer** - Our interfaces adapt SW, but aren't 1:1 copies - room for casehub extensions
2. **Decorator pattern** - Audit, caching, metrics as decorators (opt-in via config)
3. **Single ObjectMapper** - Centralized CDI bean for all JQ evaluations
4. **ConfigContext** - Holder for managers + future case-scoped metadata
5. **Lazy resolution** - Secrets resolved on-demand during JQ evaluation, NOT at YAML parse time
6. **Separation of concerns** - `marshaller` package for ObjectMapper/JQ infrastructure, `config` package for config/secrets access

---

## Core Interfaces

### ConfigManager

```java
package io.casehub.engine.internal.config;

import java.util.Collection;
import java.util.Optional;

/**
 * Provides access to configuration properties.
 * 
 * <p>Adapted from Serverless Workflow ConfigManager with Quarkus integration.
 * Used programmatically from Java code, NOT directly accessible from JQ expressions.
 * 
 * <p>Default implementation wraps MicroProfile Config API (application.properties,
 * system properties, environment variables, ConfigSources).
 */
public interface ConfigManager {

  /**
   * Get a single config value.
   *
   * @param propName property name (e.g., "casehub.timeout")
   * @param propClass target type (String, Integer, Boolean, etc.)
   * @return value if present
   */
  <T> Optional<T> config(String propName, Class<T> propClass);

  /**
   * Get a multi-valued config (comma-separated).
   *
   * @param propName property name
   * @param propClass element type
   * @return collection of values (empty if not found)
   */
  <T> Collection<T> multiConfig(String propName, Class<T> propClass);

  /**
   * List all known property names.
   *
   * @return iterable of property names
   */
  Iterable<String> names();
}
```

### SecretManager

```java
package io.casehub.engine.internal.config;

import java.util.Map;

/**
 * Resolves secrets from various backends (system properties, K8s Secrets, Vault, etc.).
 * 
 * <p>Adapted from Serverless Workflow SecretManager. Accessible from JQ expressions
 * via {@code $secret.{secretName}.{property}} syntax.
 * 
 * <p>Default implementation (ConfigSecretManager) builds secrets from ConfigManager
 * by filtering properties with {@code secretName.} prefix and creating nested maps.
 * 
 * <p>Example:
 * <pre>
 * # application.properties
 * openai.apiKey=sk-test
 * openai.organizationId=org-123
 * 
 * # JQ expression in YAML
 * apiKey: "${$secret.openai.apiKey}"  → resolves to "sk-test"
 * </pre>
 */
public interface SecretManager {

  /**
   * Resolve a secret by name.
   *
   * @param secretName secret identifier (e.g., "openai", "database")
   * @return map of secret properties (e.g., {apiKey: "sk-...", orgId: "..."})
   * @throws SecretNotFoundException if secret does not exist
   */
  Map<String, Object> secret(String secretName);
}
```

### ConfigContext

```java
package io.casehub.engine.internal.config;

/**
 * Holder for ConfigManager and SecretManager instances.
 * 
 * <p>Provides centralized access to configuration infrastructure.
 * Future: may hold case-scoped metadata for case-specific config/secrets.
 */
public interface ConfigContext {

  ConfigManager configManager();

  SecretManager secretManager();
}
```

### Exceptions

```java
package io.casehub.engine.internal.config;

/**
 * Thrown when a requested secret does not exist.
 * 
 * <p>Fail-fast behavior: JQ expressions fail immediately if secret is missing.
 * Error message must NOT expose secret values or sensitive metadata.
 */
public class SecretNotFoundException extends RuntimeException {

  private final String secretName;

  public SecretNotFoundException(String secretName) {
    super("Secret not found: " + secretName);
    this.secretName = secretName;
  }

  public String getSecretName() {
    return secretName;
  }
}
```

```java
package io.casehub.engine.internal.config;

/**
 * Thrown when config resolution fails (type conversion, validation, etc.).
 */
public class ConfigResolutionException extends RuntimeException {

  public ConfigResolutionException(String message) {
    super(message);
  }

  public ConfigResolutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

---

## Default Implementations

### QuarkusConfigManager

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/impl/QuarkusConfigManager.java`

```java
package io.casehub.engine.internal.config.impl;

import io.casehub.engine.internal.config.ConfigManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import java.util.*;

/**
 * ConfigManager implementation that wraps Quarkus MicroProfile Config.
 * 
 * <p>Resolution order (via Quarkus Config):
 * 1. System properties (-Dfoo=bar)
 * 2. Environment variables
 * 3. application.properties
 * 4. ConfigSources (K8s ConfigMaps, etc.)
 */
@ApplicationScoped
public class QuarkusConfigManager implements ConfigManager {

  @Inject
  Config config;

  @Override
  public <T> Optional<T> config(String propName, Class<T> propClass) {
    return config.getOptionalValue(propName, propClass);
  }

  @Override
  public <T> Collection<T> multiConfig(String propName, Class<T> propClass) {
    return config.getOptionalValues(propName, propClass)
        .orElse(Collections.emptyList());
  }

  @Override
  public Iterable<String> names() {
    return config.getPropertyNames();
  }
}
```

**Key features:**
- Wraps MicroProfile Config API (standard Quarkus)
- Automatic type conversion (String, Integer, Boolean, etc.)
- Multi-source resolution (system props, env vars, application.properties, ConfigSources)
- `@ApplicationScoped` CDI bean

---

### ConfigSecretManager

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/impl/ConfigSecretManager.java`

```java
package io.casehub.engine.internal.config.impl;

import io.casehub.engine.internal.config.ConfigManager;
import io.casehub.engine.internal.config.SecretManager;
import io.casehub.engine.internal.config.SecretNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

/**
 * Builds secrets from ConfigManager by filtering properties with prefix.
 * 
 * <p>Adapted from Serverless Workflow ConfigSecretManager.
 * 
 * <p>Example:
 * <pre>
 * openai.apiKey=sk-test
 * openai.organizationId=org-123
 * openai.model.name=gpt-4o
 * </pre>
 * becomes:
 * <pre>
 * {
 *   "apiKey": "sk-test",
 *   "organizationId": "org-123",
 *   "model": {
 *     "name": "gpt-4o"
 *   }
 * }
 * </pre>
 */
@ApplicationScoped
public class ConfigSecretManager implements SecretManager {

  @Inject
  ConfigManager configManager;

  @Override
  public Map<String, Object> secret(String secretName) {
    String prefix = secretName + ".";
    Map<String, Object> result = new HashMap<>();
    
    for (String propName : configManager.names()) {
      if (propName.startsWith(prefix)) {
        String key = propName.substring(prefix.length());
        configManager.config(propName, String.class)
            .ifPresent(value -> putNested(result, key, value));
      }
    }
    
    if (result.isEmpty()) {
      throw new SecretNotFoundException(secretName);
    }
    
    return result;
  }

  /**
   * Converts "enemy.name" -> nested map {enemy: {name: value}}.
   * 
   * <p>Algorithm adapted from Serverless Workflow ConfigSecretManager.
   */
  private void putNested(Map<String, Object> map, String key, Object value) {
    String[] parts = key.split("\\.", 2);
    if (parts.length == 1) {
      map.put(key, value);
    } else {
      @SuppressWarnings("unchecked")
      Map<String, Object> nested = (Map<String, Object>) 
          map.computeIfAbsent(parts[0], k -> new HashMap<>());
      putNested(nested, parts[1], value);
    }
  }
}
```

**Key features:**
- Builds nested maps from dotted property names
- Throws `SecretNotFoundException` if no properties with prefix found
- Adapted from SW logic but independent implementation
- `@ApplicationScoped` CDI bean

---

### DefaultConfigContext

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/impl/DefaultConfigContext.java`

```java
package io.casehub.engine.internal.config.impl;

import io.casehub.engine.internal.config.ConfigContext;
import io.casehub.engine.internal.config.ConfigManager;
import io.casehub.engine.internal.config.SecretManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Default CDI bean providing access to ConfigManager and SecretManager.
 */
@ApplicationScoped
public class DefaultConfigContext implements ConfigContext {

  @Inject
  ConfigManager configManager;

  @Inject
  SecretManager secretManager;

  @Override
  public ConfigManager configManager() {
    return configManager;
  }

  @Override
  public SecretManager secretManager() {
    return secretManager;
  }
}
```

---

### ServerlessWorkflowAdapter (Optional Bridge)

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/impl/ServerlessWorkflowAdapter.java`

```java
package io.casehub.engine.internal.config.impl;

import io.serverlessworkflow.impl.config.ConfigManager as SwConfigManager;
import io.serverlessworkflow.impl.config.SecretManager as SwSecretManager;

/**
 * Adapter/bridge to Serverless Workflow config classes.
 * 
 * <p>Allows reusing SW's ConfigSecretManager logic while keeping our interfaces.
 * Used internally if we want to delegate complex nesting logic to SW instead 
 * of reimplementing.
 * 
 * <p>Note: This is optional. We can implement nesting logic independently
 * (as shown in ConfigSecretManager) or delegate to SW via this adapter.
 */
class ServerlessWorkflowAdapter {
  
  /**
   * Adapt our ConfigManager to SW's interface.
   */
  static SwConfigManager adaptConfigManager(
      io.casehub.engine.internal.config.ConfigManager our) {
    
    return new SwConfigManager() {
      @Override
      public <T> Optional<T> config(String propName, Class<T> propClass) {
        return our.config(propName, propClass);
      }

      @Override
      public <T> Collection<T> multiConfig(String propName, Class<T> propClass) {
        return our.multiConfig(propName, propClass);
      }

      @Override
      public Iterable<String> names() {
        return our.names();
      }
    };
  }
  
  /**
   * Adapt our SecretManager to SW's interface.
   */
  static SwSecretManager adaptSecretManager(
      io.casehub.engine.internal.config.SecretManager our) {
    
    return new SwSecretManager() {
      @Override
      public Map<String, Object> secret(String secretName) {
        return our.secret(secretName);
      }
    };
  }
}
```

**Usage decision:** We can implement `ConfigSecretManager` nesting logic independently (as shown above) OR delegate to SW's `ConfigSecretManager` via this adapter. Independent implementation is recommended to avoid tight coupling.

---

## Decorator Implementations

### AuditingSecretManager

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/decorator/AuditingSecretManager.java`

```java
package io.casehub.engine.internal.config.decorator;

import io.casehub.engine.internal.config.SecretManager;
import io.casehub.engine.internal.config.SecretNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.Map;

/**
 * Decorator that logs secret access for compliance auditing.
 * 
 * <p>Logs which secrets are accessed (NOT their values) for EU AI Act/GDPR compliance.
 * 
 * <p>Enable/disable via:
 * <pre>
 * casehub.config.audit.secrets=true  (default: true)
 * </pre>
 */
@Decorator
@ApplicationScoped
public abstract class AuditingSecretManager implements SecretManager {

  private static final Logger log = Logger.getLogger(AuditingSecretManager.class);

  @Inject
  @Delegate
  SecretManager delegate;

  @Inject
  io.casehub.engine.internal.config.ConfigManager configManager;

  @Override
  public Map<String, Object> secret(String secretName) {
    boolean auditEnabled = configManager
        .config("casehub.config.audit.secrets", Boolean.class)
        .orElse(true);

    if (auditEnabled) {
      log.infof("Secret accessed: %s [caller: %s]", 
          secretName, 
          getCallerClass());
    }

    try {
      Map<String, Object> result = delegate.secret(secretName);
      if (auditEnabled) {
        log.debugf("Secret resolved: %s (keys: %s)", 
            secretName, 
            result.keySet());
      }
      return result;
    } catch (SecretNotFoundException e) {
      if (auditEnabled) {
        log.warnf("Secret not found: %s", secretName);
      }
      throw e;
    }
  }

  private String getCallerClass() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    // Skip getStackTrace, getCallerClass, secret method
    return stack.length > 3 ? stack[3].getClassName() : "unknown";
  }
}
```

**Key features:**
- Logs secret access attempts (name only, never values)
- Logs successful resolution (with key names, not values)
- Logs failures (SecretNotFoundException)
- Configurable enable/disable
- **Security:** Never logs actual secret values
- **Compliance:** Audit trail for EU AI Act/GDPR

---

### CachedSecretManager

**Location:** `runtime/src/main/java/io/casehub/engine/internal/config/decorator/CachedSecretManager.java`

```java
package io.casehub.engine.internal.config.decorator;

import io.casehub.engine.internal.config.SecretManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorator that caches secrets with configurable TTL.
 * 
 * <p>Useful for external secret stores (K8s, Vault) to reduce API calls.
 * 
 * <p>Configuration:
 * <pre>
 * casehub.config.secrets.cache.ttl=PT5M  (5 minutes, ISO-8601 duration)
 * casehub.config.secrets.cache.ttl=PT0S  (disabled, default)
 * </pre>
 */
@Decorator
@ApplicationScoped
public abstract class CachedSecretManager implements SecretManager {

  @Inject
  @Delegate
  SecretManager delegate;

  @Inject
  io.casehub.engine.internal.config.ConfigManager configManager;

  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  @Override
  public Map<String, Object> secret(String secretName) {
    Duration ttl = getTtl();

    if (ttl.isZero() || ttl.isNegative()) {
      return delegate.secret(secretName); // caching disabled
    }

    CacheEntry entry = cache.get(secretName);
    if (entry != null && entry.isValid(ttl)) {
      return entry.value;
    }

    Map<String, Object> value = delegate.secret(secretName);
    cache.put(secretName, new CacheEntry(value, Instant.now()));
    return value;
  }

  private Duration getTtl() {
    return configManager
        .config("casehub.config.secrets.cache.ttl", String.class)
        .map(Duration::parse)
        .orElse(Duration.ZERO);
  }

  private static class CacheEntry {
    final Map<String, Object> value;
    final Instant timestamp;

    CacheEntry(Map<String, Object> value, Instant timestamp) {
      this.value = Map.copyOf(value); // defensive copy
      this.timestamp = timestamp;
    }

    boolean isValid(Duration ttl) {
      return Instant.now().isBefore(timestamp.plus(ttl));
    }
  }
}
```

**Key features:**
- Caches resolved secrets in-memory
- Configurable TTL (ISO-8601 duration)
- Disabled by default (PT0S)
- Thread-safe (ConcurrentHashMap)
- Defensive copy of secret map
- **Performance:** Reduces external store API calls

---

## JQ Integration (Marshaller Package)

### CaseHubObjectMapperProducer

**Location:** `runtime/src/main/java/io/casehub/engine/internal/marshaller/CaseHubObjectMapperProducer.java`

```java
package io.casehub.engine.internal.marshaller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.engine.internal.config.ConfigContext;
import io.casehub.engine.internal.marshaller.jq.JqScopeInjector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Produces centralized ObjectMapper configured for CaseHub YAML/JSON processing.
 * 
 * <p>Configures:
 * - JQ scope injection ($secret, $config functions)
 * - YAML parsing
 * - Future: other Jackson modules, custom deserializers
 * 
 * <p>All code should inject this ObjectMapper instead of creating manually.
 */
@ApplicationScoped
public class CaseHubObjectMapperProducer {

  @Inject
  ConfigContext configContext;

  @Produces
  @Singleton
  public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    
    // Configure JQ scope with $secret and $config functions
    JqScopeInjector.configure(mapper, configContext);
    
    // Future: other Jackson modules, custom deserializers, etc.
    
    return mapper;
  }
}
```

**Key responsibilities:**
- Centralized ObjectMapper creation
- JQ scope configuration
- Future extension point for Jackson customization

---

### JqScopeInjector

**Location:** `runtime/src/main/java/io/casehub/engine/internal/marshaller/jq/JqScopeInjector.java`

```java
package io.casehub.engine.internal.marshaller.jq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.internal.config.ConfigContext;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Function;
import net.thisptr.jackson.jq.PathOutput;
import net.thisptr.jackson.jq.path.Path;
import net.thisptr.jackson.jq.Versions;
import java.util.List;
import java.util.Map;

/**
 * Configures jackson-jq scope with $secret and $config functions.
 * 
 * <p>Inspired by Serverless Workflow's JQ scope injection pattern:
 * <pre>
 * childScope.setValue("secret", new FunctionJsonNode(
 *     k -> workflow.definition().application().secretManager().secret(k)));
 * </pre>
 * 
 * <p>Functions are evaluated at runtime during JQ expression evaluation.
 */
public class JqScopeInjector {

  /**
   * Configure ObjectMapper's JQ context with $secret function.
   * 
   * @param mapper ObjectMapper to configure
   * @param configContext provides access to ConfigManager and SecretManager
   */
  public static void configure(ObjectMapper mapper, ConfigContext configContext) {
    // Note: Actual integration depends on how JQ expressions are evaluated
    // in bindings/milestones/goals. This is a conceptual design.
    // 
    // The pattern is:
    // 1. Create or get root JQ Scope
    // 2. Inject $secret function
    // 3. Attach scope to JQ evaluation context
    
    Scope rootScope = Scope.newEmptyScope();
    rootScope.setValue("secret", new SecretFunction(configContext));
    
    // Future: $config function if needed for JQ access to configs
    // rootScope.setValue("config", new ConfigFunction(configContext));
    
    // Attach scope to ObjectMapper's JQ context
    // Implementation detail: depends on actual JQ evaluation mechanism
  }

  /**
   * JQ function that resolves secrets: $secret.openai.apiKey
   * 
   * <p>Usage in YAML:
   * <pre>
   * apiKey: "${$secret.openai.apiKey}"
   * </pre>
   * 
   * <p>Evaluation:
   * <pre>
   * JQ: $secret("openai") → {apiKey: "sk-...", orgId: "..."}
   * JQ: $secret("openai").apiKey → "sk-..."
   * </pre>
   */
  private static class SecretFunction implements Function {
    private final ConfigContext configContext;

    SecretFunction(ConfigContext configContext) {
      this.configContext = configContext;
    }

    @Override
    public void apply(Scope scope, List<JsonNode> args, JsonNode input, 
                     Path path, PathOutput output, Versions version) {
      if (args.size() != 1 || !args.get(0).isTextual()) {
        throw new IllegalArgumentException(
            "$secret requires one string argument: $secret.secretName.property");
      }
      
      String secretName = args.get(0).asText();
      Map<String, Object> secret = configContext.secretManager().secret(secretName);
      
      // Convert Map to JsonNode
      ObjectMapper mapper = new ObjectMapper();
      JsonNode secretNode = mapper.valueToTree(secret);
      
      output.emit(secretNode, null);
    }
  }
}
```

**Key responsibilities:**
- Inject `$secret` function into JQ scope
- Delegate to `SecretManager` for resolution
- Convert resolved Map to JsonNode for JQ
- Error handling (SecretNotFoundException propagates)

**Note:** Actual JQ integration details depend on how expressions are currently evaluated in the codebase. This design assumes jackson-jq library integration. Implementation will need to adapt to the actual JQ evaluation mechanism in bindings, milestones, goals, agent transforms.

---

### Usage in Existing Code

**Before (manual ObjectMapper creation):**
```java
public class CaseDefinitionYamlMapper {
  private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
  
  public CaseDefinition fromYaml(String yaml) {
    return mapper.readValue(yaml, CaseDefinition.class);
  }
}
```

**After (inject centralized ObjectMapper):**
```java
public class CaseDefinitionYamlMapper {
  
  @Inject
  ObjectMapper mapper;  // ✅ Centralized, JQ-configured
  
  public CaseDefinition fromYaml(String yaml) {
    return mapper.readValue(yaml, CaseDefinition.class);
  }
}
```

**JQ expressions in YAML now have $secret available:**
```yaml
workers:
  - name: sentiment-analyzer
    agent:
      systemPrompt: "Analyze sentiment"
      inputSchema: "${.context.text}"
      outputSchema: "${.sentiment}"
      model:
        openai:
          apiKey: "${$secret.openai.apiKey}"  # ✅ Resolved at runtime
          modelName: "gpt-4o-mini"
```

---

## Configuration Properties & Error Handling

### Configuration Properties

**application.properties:**

```properties
# ============================================================================
# Config & Secrets Management
# ============================================================================

# --- Audit Logging ---
# Enable/disable secret access auditing (compliance logging)
# Default: true (enabled for EU AI Act/GDPR compliance)
casehub.config.audit.secrets=true

# --- Secret Caching ---
# TTL for secret cache (ISO-8601 duration: PT5M = 5 minutes, PT0S = disabled)
# Default: PT0S (disabled - no caching)
# Recommended for external stores (K8s, Vault): PT5M to PT15M
casehub.config.secrets.cache.ttl=PT0S

# --- Decorator Enablement ---
# Enable AuditingSecretManager decorator
# Default: true (compliance requirement)
casehub.config.decorators.auditing.enabled=true

# Enable CachedSecretManager decorator
# Default: false (enabled automatically if cache.ttl > 0)
casehub.config.decorators.caching.enabled=false

# ============================================================================
# Example: Secret Configuration via System Properties
# ============================================================================

# OpenAI secrets
openai.apiKey=sk-...
openai.organizationId=org-...
openai.model.name=gpt-4o-mini
openai.model.temperature=0.7

# Anthropic secrets
anthropic.apiKey=sk-ant-...
anthropic.version=2023-06-01

# Ollama configuration
ollama.baseUrl=http://localhost:11434
ollama.modelName=llama2

# Database secrets (future use)
database.username=admin
database.password=secret123
database.host=localhost
database.port=5432
```

### Decorator Configuration Matrix

| Property | Default | Effect |
|----------|---------|--------|
| `casehub.config.audit.secrets=true` | `true` | AuditingSecretManager logs all secret access |
| `casehub.config.audit.secrets=false` | - | Audit logging disabled (not recommended) |
| `casehub.config.secrets.cache.ttl=PT5M` | `PT0S` | Cache secrets for 5 minutes |
| `casehub.config.secrets.cache.ttl=PT0S` | ✓ | Caching disabled |
| `casehub.config.decorators.auditing.enabled=false` | `true` | Disable AuditingSecretManager decorator |
| `casehub.config.decorators.caching.enabled=false` | `false` | Disable CachedSecretManager decorator |

**Recommendation:** Keep audit logging enabled in production for compliance. Enable caching for external secret stores (K8s, Vault) with appropriate TTL.

---

### Error Handling Strategy

#### Fail-Fast Behavior

**Philosophy:** Secrets must be available before Case execution starts. Missing secrets indicate misconfiguration and should fail immediately.

**Error Flow:**
```
YAML definition with JQ expression: "${$secret.openai.apiKey}"
  ↓
JQ evaluates expression at runtime (during Case definition load or binding evaluation)
  ↓
Calls $secret function with "openai"
  ↓
SecretManager.secret("openai") called
  ↓
AuditingSecretManager logs access attempt
  ↓
If not found: throws SecretNotFoundException
  ↓
JQ evaluation fails with clear error message
  ↓
Case definition load fails (fail-fast)
```

#### Security Considerations

**Rules:**
1. **Never log actual secret values** - only secret names and access metadata
2. **Mask secrets in toString()** - if secret objects ever printed
3. **Error messages reveal only secret name** - not content, not paths
4. **Audit trail for compliance** - who accessed what, when (but not values)
5. **Don't cache failures** - retry on next access if secret becomes available

**Example error message:**
```
✅ Good: "Secret not found: openai"
❌ Bad: "Secret 'openai' not found at path /etc/secrets/openai.yaml"
❌ Bad: "Failed to read secret: sk-proj-abc..." (leaks value)
```

#### Error Handling in Code

**SecretNotFoundException handling:**
```java
try {
  Map<String, Object> secret = secretManager.secret("openai");
  String apiKey = (String) secret.get("apiKey");
} catch (SecretNotFoundException e) {
  // Logged by AuditingSecretManager
  // Propagate - don't swallow
  throw new ConfigResolutionException(
      "Failed to resolve secret: " + e.getSecretName(), e);
}
```

**ConfigResolutionException handling:**
```java
try {
  Integer timeout = configManager.config("casehub.timeout", Integer.class)
      .orElseThrow(() -> new ConfigResolutionException("timeout not configured"));
} catch (ConfigResolutionException e) {
  // Log and fail-fast
  log.error("Configuration error", e);
  throw e;
}
```

---

## Testing Strategy

### Unit Tests

#### ConfigManager Tests

**File:** `runtime/src/test/java/io/casehub/engine/internal/config/impl/QuarkusConfigManagerTest.java`

```java
@QuarkusTest
class QuarkusConfigManagerTest {
  
  @Inject
  ConfigManager configManager;
  
  @Test
  void shouldResolveFromApplicationProperties() {
    // application.properties: test.timeout=5000
    Optional<Integer> timeout = configManager.config("test.timeout", Integer.class);
    assertThat(timeout).hasValue(5000);
  }
  
  @Test
  void shouldResolveFromSystemProperties() {
    System.setProperty("test.flag", "true");
    Optional<Boolean> flag = configManager.config("test.flag", Boolean.class);
    assertThat(flag).hasValue(true);
  }
  
  @Test
  void shouldResolveFromEnvironmentVariables() {
    // Assuming TEST_ENV_VAR is set in test environment
    Optional<String> value = configManager.config("TEST_ENV_VAR", String.class);
    assertThat(value).isPresent();
  }
  
  @Test
  void shouldHandleMultiValues() {
    // test.items=a,b,c
    Collection<String> items = configManager.multiConfig("test.items", String.class);
    assertThat(items).containsExactly("a", "b", "c");
  }
  
  @Test
  void shouldReturnEmptyForUnknownProperty() {
    Optional<String> unknown = configManager.config("unknown.property", String.class);
    assertThat(unknown).isEmpty();
  }
  
  @Test
  void shouldHandleTypeConversion() {
    System.setProperty("test.number", "42");
    assertThat(configManager.config("test.number", Integer.class)).hasValue(42);
    assertThat(configManager.config("test.number", String.class)).hasValue("42");
  }
}
```

#### SecretManager Tests

**File:** `runtime/src/test/java/io/casehub/engine/internal/config/impl/ConfigSecretManagerTest.java`

```java
@QuarkusTest
class ConfigSecretManagerTest {
  
  @Inject
  SecretManager secretManager;
  
  @BeforeEach
  void setup() {
    System.setProperty("openai.apiKey", "sk-test");
    System.setProperty("openai.organizationId", "org-test");
  }
  
  @AfterEach
  void cleanup() {
    System.clearProperty("openai.apiKey");
    System.clearProperty("openai.organizationId");
  }
  
  @Test
  void shouldBuildSecretFromPrefixedProperties() {
    Map<String, Object> secret = secretManager.secret("openai");
    
    assertThat(secret)
        .containsEntry("apiKey", "sk-test")
        .containsEntry("organizationId", "org-test");
  }
  
  @Test
  void shouldBuildNestedMaps() {
    System.setProperty("db.credentials.username", "admin");
    System.setProperty("db.credentials.password", "secret");
    System.setProperty("db.connection.host", "localhost");
    
    Map<String, Object> secret = secretManager.secret("db");
    
    assertThat(secret).containsKeys("credentials", "connection");
    
    @SuppressWarnings("unchecked")
    Map<String, Object> credentials = (Map<String, Object>) secret.get("credentials");
    assertThat(credentials)
        .containsEntry("username", "admin")
        .containsEntry("password", "secret");
    
    @SuppressWarnings("unchecked")
    Map<String, Object> connection = (Map<String, Object>) secret.get("connection");
    assertThat(connection).containsEntry("host", "localhost");
  }
  
  @Test
  void shouldThrowWhenSecretNotFound() {
    assertThatThrownBy(() -> secretManager.secret("nonexistent"))
        .isInstanceOf(SecretNotFoundException.class)
        .hasMessageContaining("nonexistent");
  }
  
  @Test
  void shouldHandleSingleLevelProperties() {
    System.setProperty("simple.key", "value");
    Map<String, Object> secret = secretManager.secret("simple");
    assertThat(secret).containsEntry("key", "value");
  }
}
```

#### Decorator Tests

**File:** `runtime/src/test/java/io/casehub/engine/internal/config/decorator/AuditingSecretManagerTest.java`

```java
@QuarkusTest
class AuditingSecretManagerTest {
  
  @Inject
  SecretManager secretManager; // decorated instance
  
  @Test
  void shouldLogSecretAccess() {
    LogCapture logCapture = LogCapture.forClass(AuditingSecretManager.class);
    
    System.setProperty("test.key", "value");
    secretManager.secret("test");
    
    assertThat(logCapture.events())
        .anyMatch(e -> e.getMessage().contains("Secret accessed: test"));
  }
  
  @Test
  void shouldLogSuccessfulResolution() {
    LogCapture logCapture = LogCapture.forClass(AuditingSecretManager.class);
    
    System.setProperty("test.key", "value");
    Map<String, Object> secret = secretManager.secret("test");
    
    assertThat(logCapture.events())
        .anyMatch(e -> e.getMessage().contains("Secret resolved: test"));
  }
  
  @Test
  void shouldLogFailures() {
    LogCapture logCapture = LogCapture.forClass(AuditingSecretManager.class);
    
    assertThatThrownBy(() -> secretManager.secret("nonexistent"))
        .isInstanceOf(SecretNotFoundException.class);
    
    assertThat(logCapture.events())
        .anyMatch(e -> e.getMessage().contains("Secret not found: nonexistent"));
  }
  
  @Test
  void shouldRespectAuditDisabledConfig() {
    System.setProperty("casehub.config.audit.secrets", "false");
    LogCapture logCapture = LogCapture.forClass(AuditingSecretManager.class);
    
    System.setProperty("test.key", "value");
    secretManager.secret("test");
    
    // Should not log when disabled
    assertThat(logCapture.events()).isEmpty();
  }
}
```

**File:** `runtime/src/test/java/io/casehub/engine/internal/config/decorator/CachedSecretManagerTest.java`

```java
@QuarkusTest
class CachedSecretManagerTest {
  
  @Inject
  SecretManager secretManager;
  
  @Test
  void shouldCacheWhenTtlConfigured() {
    System.setProperty("casehub.config.secrets.cache.ttl", "PT5M");
    System.setProperty("test.key", "value1");
    
    Map<String, Object> first = secretManager.secret("test");
    
    // Change underlying value
    System.setProperty("test.key", "value2");
    
    Map<String, Object> second = secretManager.secret("test");
    
    // Should return cached value
    assertThat(first).isEqualTo(second);
    assertThat(second).containsEntry("key", "value1");
  }
  
  @Test
  void shouldNotCacheWhenTtlZero() {
    System.setProperty("casehub.config.secrets.cache.ttl", "PT0S");
    System.setProperty("test.key", "value1");
    
    Map<String, Object> first = secretManager.secret("test");
    
    // Change underlying value
    System.setProperty("test.key", "value2");
    
    Map<String, Object> second = secretManager.secret("test");
    
    // Should reflect new value (no caching)
    assertThat(second).containsEntry("key", "value2");
  }
  
  @Test
  void shouldExpireAfterTtl() throws InterruptedException {
    System.setProperty("casehub.config.secrets.cache.ttl", "PT1S"); // 1 second
    System.setProperty("test.key", "value1");
    
    Map<String, Object> first = secretManager.secret("test");
    
    Thread.sleep(1100); // Wait for TTL expiration
    
    System.setProperty("test.key", "value2");
    Map<String, Object> second = secretManager.secret("test");
    
    // Should reflect new value after expiration
    assertThat(second).containsEntry("key", "value2");
  }
}
```

---

### Integration Tests

#### JQ Expression Resolution

**File:** `runtime/src/test/java/io/casehub/engine/internal/marshaller/JqSecretResolutionIntegrationTest.java`

```java
@QuarkusTest
class JqSecretResolutionIntegrationTest {
  
  @Inject
  ObjectMapper objectMapper;
  
  @BeforeEach
  void setup() {
    System.setProperty("openai.apiKey", "sk-test-key");
    System.setProperty("openai.modelName", "gpt-4o-mini");
    System.setProperty("openai.organizationId", "org-test");
  }
  
  @Test
  void shouldResolveSecretInYamlAgentDefinition() throws Exception {
    String yaml = """
        dsl: "0.1.0"
        namespace: test
        name: agent-test
        version: "1.0.0"
        spec:
          workers:
            - name: ai-worker
              capabilities: [analyze]
              agent:
                systemPrompt: "You are a test agent"
                inputSchema: "${.input}"
                outputSchema: "${.output}"
                model:
                  openai:
                    apiKey: "${$secret.openai.apiKey}"
                    modelName: "${$secret.openai.modelName}"
                    organizationId: "${$secret.openai.organizationId}"
        """;
    
    CaseDefinition definition = objectMapper.readValue(yaml, CaseDefinition.class);
    Worker worker = definition.getSpec().getWorkers().get(0);
    
    // At runtime, JQ expression should resolve
    assertThat(worker).isNotNull();
    assertThat(worker.getName()).isEqualTo("ai-worker");
    
    // Note: Actual resolution verification depends on when JQ expressions are evaluated
    // This test verifies ObjectMapper is configured correctly
  }
  
  @Test
  void shouldFailWhenSecretMissing() {
    String yaml = """
        dsl: "0.1.0"
        namespace: test
        name: missing-secret-test
        version: "1.0.0"
        spec:
          workers:
            - name: worker
              capabilities: [test]
              agent:
                systemPrompt: "test"
                inputSchema: "${.input}"
                outputSchema: "${.output}"
                model:
                  openai:
                    apiKey: "${$secret.nonexistent.key}"
        """;
    
    // Should fail at JQ evaluation time, not at parse time
    assertThatThrownBy(() -> {
      CaseDefinition def = objectMapper.readValue(yaml, CaseDefinition.class);
      // Trigger JQ evaluation (depends on implementation)
      evaluateJqExpression(def.getWorkers().get(0).getAgent().getModel().getOpenai().getApiKey());
    }).hasCauseInstanceOf(SecretNotFoundException.class);
  }
  
  @Test
  void shouldResolveNestedSecretProperties() throws Exception {
    System.setProperty("api.credentials.key", "test-key");
    System.setProperty("api.credentials.secret", "test-secret");
    
    String yaml = """
        dsl: "0.1.0"
        namespace: test
        name: nested-test
        version: "1.0.0"
        spec:
          workers:
            - name: worker
              capabilities: [test]
              agent:
                systemPrompt: "test"
                inputSchema: "${$secret.api.credentials.key}"
                outputSchema: "${$secret.api.credentials.secret}"
                model:
                  openai:
                    apiKey: "dummy"
        """;
    
    CaseDefinition definition = objectMapper.readValue(yaml, CaseDefinition.class);
    assertThat(definition).isNotNull();
  }
}
```

---

### Contract Tests (SPI)

**File:** `common/src/test/java/io/casehub/engine/internal/config/ConfigManagerContractTest.java`

```java
/**
 * Contract tests for ConfigManager implementations.
 * 
 * <p>Similar pattern to WorkerProvisionerContractTest.
 * All ConfigManager implementations must pass these tests.
 */
abstract class ConfigManagerContractTest {
  
  protected abstract ConfigManager createConfigManager();
  
  @Test
  void shouldReturnEmptyForUnknownProperty() {
    ConfigManager manager = createConfigManager();
    assertThat(manager.config("unknown.property", String.class)).isEmpty();
  }
  
  @Test
  void shouldSupportStringType() {
    ConfigManager manager = createConfigManager();
    // Assuming test.string is configured
    Optional<String> value = manager.config("test.string", String.class);
    assertThat(value).isPresent();
  }
  
  @Test
  void shouldSupportIntegerType() {
    ConfigManager manager = createConfigManager();
    // Assuming test.integer=42 is configured
    Optional<Integer> value = manager.config("test.integer", Integer.class);
    assertThat(value).hasValue(42);
  }
  
  @Test
  void shouldSupportBooleanType() {
    ConfigManager manager = createConfigManager();
    // Assuming test.boolean=true is configured
    Optional<Boolean> value = manager.config("test.boolean", Boolean.class);
    assertThat(value).hasValue(true);
  }
  
  @Test
  void shouldHandleMultiValues() {
    ConfigManager manager = createConfigManager();
    // Assuming test.list=a,b,c is configured
    Collection<String> values = manager.multiConfig("test.list", String.class);
    assertThat(values).containsExactly("a", "b", "c");
  }
  
  @Test
  void shouldListPropertyNames() {
    ConfigManager manager = createConfigManager();
    Iterable<String> names = manager.names();
    assertThat(names).isNotEmpty();
  }
}

/**
 * Contract test instance for QuarkusConfigManager.
 */
@QuarkusTest
class QuarkusConfigManagerContractTest extends ConfigManagerContractTest {
  
  @Inject
  ConfigManager configManager;
  
  @Override
  protected ConfigManager createConfigManager() {
    return configManager;
  }
}
```

**File:** `common/src/test/java/io/casehub/engine/internal/config/SecretManagerContractTest.java`

```java
/**
 * Contract tests for SecretManager implementations.
 */
abstract class SecretManagerContractTest {
  
  protected abstract SecretManager createSecretManager();
  
  @Test
  void shouldResolveExistingSecret() {
    SecretManager manager = createSecretManager();
    // Assuming "test" secret is configured
    Map<String, Object> secret = manager.secret("test");
    assertThat(secret).isNotEmpty();
  }
  
  @Test
  void shouldThrowForMissingSecret() {
    SecretManager manager = createSecretManager();
    assertThatThrownBy(() -> manager.secret("nonexistent"))
        .isInstanceOf(SecretNotFoundException.class);
  }
  
  @Test
  void shouldBuildNestedMaps() {
    SecretManager manager = createSecretManager();
    // Assuming nested.a.b=value is configured
    Map<String, Object> secret = manager.secret("nested");
    assertThat(secret).containsKey("a");
  }
  
  @Test
  void shouldReturnImmutableMap() {
    SecretManager manager = createSecretManager();
    Map<String, Object> secret = manager.secret("test");
    
    // Should not be modifiable (defensive copy)
    assertThatThrownBy(() -> secret.put("key", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
```

---

### Test Coverage Goals

| Component | Coverage Target | Critical Paths |
|-----------|----------------|----------------|
| **ConfigManager** | >90% | System props, env vars, multi-values, type conversion |
| **SecretManager** | >90% | Nested maps, missing secrets, empty results |
| **Decorators** | >85% | Audit logging, cache hit/miss, TTL expiration |
| **JQ Integration** | >80% | Secret resolution, error propagation, missing secrets |
| **Contract Tests** | 100% | All SPI contract requirements |

**Testing Anti-Patterns to Avoid:**
- ❌ Don't test with real external secrets (K8s, Vault) in unit tests
- ❌ Don't log actual secret values even in test output
- ❌ Don't skip error path testing (fail-fast is critical)
- ❌ Don't test deserialization without JQ evaluation

**Recommended Testing Tools:**
- ✅ `@QuarkusTest` for CDI integration tests
- ✅ `LogCapture` for audit logging verification
- ✅ `assertThatThrownBy` for exception testing
- ✅ `@BeforeEach/@AfterEach` for system property cleanup

---

## Migration Path & Implementation Phases

### Current State Analysis

**Existing code using direct `System.getenv()`:**

```java
// api/src/main/java/io/casehub/api/model/ai/openai/OpenAiChatModelProvider.java
public OpenAiChatModelProvider() {
  this.apiKey = System.getenv("OPENAI_API_KEY");  // ❌ Direct access
  this.modelName = "gpt-4o-mini";
}
```

Similar pattern in:
- `AnthropicChatModelProvider` → `System.getenv("ANTHROPIC_API_KEY")`
- `OllamaChatModelProvider` → `System.getenv("OLLAMA_BASE_URL")`, `System.getenv("OLLAMA_MODEL")`
- `GoogleAiGeminiChatModelProvider` → `System.getenv("GOOGLE_API_KEY")`
- `MistralAiChatModelProvider` → `System.getenv("MISTRAL_API_KEY")`

**YAML schema with documented but unimplemented placeholder syntax:**

```yaml
# schema/src/main/resources/schema/CaseDefinition.yaml
apiKey:
  type: string
  description: "OpenAI API key (can use ${ENV_VAR} syntax)"  # ❌ Documented but not implemented
```

---

### Phase 1: Infrastructure Foundation

**Goal:** Config/Secrets infrastructure exists but not yet used in YAML.

**Tasks:**

1. **Define interfaces** (`common/src/main/java/io/casehub/engine/internal/config/`):
   - `ConfigManager.java`
   - `SecretManager.java`
   - `ConfigContext.java`
   - `SecretNotFoundException.java`
   - `ConfigResolutionException.java`

2. **Implement core classes** (`runtime/src/main/java/io/casehub/engine/internal/config/impl/`):
   - `QuarkusConfigManager` (wraps MicroProfile Config)
   - `ConfigSecretManager` (builds secrets from ConfigManager)
   - `DefaultConfigContext` (CDI bean holder)
   - `ServerlessWorkflowAdapter` (optional bridge)

3. **Implement decorators** (`runtime/src/main/java/io/casehub/engine/internal/config/decorator/`):
   - `AuditingSecretManager`
   - `CachedSecretManager`

4. **Unit tests**:
   - `QuarkusConfigManagerTest`
   - `ConfigSecretManagerTest`
   - `AuditingSecretManagerTest`
   - `CachedSecretManagerTest`

5. **Contract tests** (`common/src/test/java/io/casehub/engine/internal/config/`):
   - `ConfigManagerContractTest`
   - `SecretManagerContractTest`

**Acceptance Criteria:**
- ✅ All interfaces defined in `common`
- ✅ All implementations in `runtime`
- ✅ Unit tests passing (>90% coverage)
- ✅ Contract tests passing (100%)
- ✅ CDI beans injectable (`@Inject ConfigContext`)
- ✅ No YAML integration yet - just infrastructure

**Estimated Effort:** 3-5 days

---

### Phase 2: ObjectMapper Centralization

**Goal:** Single `@ApplicationScoped ObjectMapper` with JQ scope configured.

**Tasks:**

1. **Create marshaller package** (`runtime/src/main/java/io/casehub/engine/internal/marshaller/`):
   - `CaseHubObjectMapperProducer` (produces ObjectMapper)
   - `jq/JqScopeInjector` (injects $secret function)

2. **Update existing code** to inject ObjectMapper:
   - `CaseDefinitionYamlMapper` - replace manual ObjectMapper creation with `@Inject`
   - Any other classes creating ObjectMapper manually

3. **Integration tests**:
   - `JqSecretResolutionIntegrationTest`
   - Verify `$secret` function available in JQ scope

4. **Document JQ integration**:
   - How JQ expressions are evaluated
   - Where `$secret` function is injected
   - Integration points (bindings, milestones, goals, agent transforms)

**Existing code changes:**

```java
// Before:
public class CaseDefinitionYamlMapper {
  private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
}

// After:
public class CaseDefinitionYamlMapper {
  @Inject
  ObjectMapper mapper;  // ✅ Centralized, JQ-configured
}
```

**Acceptance Criteria:**
- ✅ `CaseHubObjectMapperProducer` produces singleton ObjectMapper
- ✅ `JqScopeInjector` injects `$secret` function into JQ scope
- ✅ All manual ObjectMapper creation replaced with injection
- ✅ Integration tests verify JQ resolution works
- ✅ Documentation explains JQ integration

**Estimated Effort:** 2-3 days

---

### Phase 3: YAML Schema Updates & Documentation

**Goal:** YAML schema documents JQ secret syntax, migration guide available.

**Tasks:**

1. **Update YAML schema** (`schema/src/main/resources/schema/CaseDefinition.yaml`):
   - Update field descriptions with JQ expression examples
   - Document `${$secret.*}` syntax
   - Add configuration examples

2. **Update test YAML files**:
   - Convert test fixtures to use `${$secret.*}` syntax
   - Add tests for various secret scenarios

3. **Create documentation**:
   - `docs/config-secrets-management.md` (complete guide)
   - Update `docs/secret-manager-spi.md` (mark as implemented)
   - Add examples to README

4. **Migration guide**:
   - How to migrate from hardcoded secrets
   - How to configure secrets (application.properties)
   - How to enable/disable decorators

**Schema changes:**

```yaml
# Before:
apiKey:
  type: string
  description: "OpenAI API key (can use ${ENV_VAR} syntax)"

# After:
apiKey:
  type: string
  description: |
    OpenAI API key.
    Supports JQ expressions: "${$secret.openai.apiKey}"
    
    Example configuration (application.properties):
      openai.apiKey=sk-...
      openai.organizationId=org-...
    
    The secret is resolved at runtime during JQ expression evaluation.
```

**Example YAML:**

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
            temperature: 0.7
```

**Acceptance Criteria:**
- ✅ YAML schema documents JQ secret syntax
- ✅ Test fixtures use `${$secret.*}` syntax
- ✅ Complete documentation available
- ✅ Migration guide published
- ✅ Examples in README

**Estimated Effort:** 2-3 days

---

### Phase 4: ChatModelProvider Refactoring (Optional)

**Goal:** Programmatic code uses ConfigManager instead of direct `System.getenv()`.

**Note:** This phase is **optional**. ChatModelProviders are instantiated programmatically, not through YAML, so JQ resolution doesn't apply. The main value is consistency and future extensibility.

**Tasks:**

1. **Refactor ChatModelProvider classes**:
   - `OpenAiChatModelProvider`
   - `AnthropicChatModelProvider`
   - `OllamaChatModelProvider`
   - `GoogleAiGeminiChatModelProvider`
   - `MistralAiChatModelProvider`

2. **Backward compatibility**:
   - Fallback to `System.getenv()` if ConfigManager returns empty
   - No breaking changes for existing deployments

3. **Tests**:
   - Verify ConfigManager used
   - Verify fallback works
   - Verify backward compatibility

**Migration example:**

```java
// Before:
public OpenAiChatModelProvider() {
  this.apiKey = System.getenv("OPENAI_API_KEY");
}

// After (with backward compatibility):
@Inject
ConfigContext configContext;

public OpenAiChatModelProvider() {
  this.apiKey = configContext.configManager()
      .config("openai.apiKey", String.class)
      .or(() -> Optional.ofNullable(System.getenv("OPENAI_API_KEY")))  // fallback
      .orElse(null);
}
```

**Acceptance Criteria:**
- ✅ All ChatModelProviders use ConfigManager
- ✅ Fallback to `System.getenv()` works
- ✅ No breaking changes
- ✅ Tests verify both paths

**Estimated Effort:** 1-2 days

**Recommendation:** Skip this phase in MVP. Focus on YAML JQ resolution (Phases 1-3). Add programmatic ConfigManager usage in future if needed.

---

### Backward Compatibility Guarantees

**No Breaking Changes:**

- ✅ Existing `System.getenv()` calls continue to work
- ✅ Environment variables accessible through Quarkus Config (MicroProfile Config auto-includes env vars)
- ✅ Existing YAML with hardcoded secrets continues to work (plain strings still valid)
- ✅ Existing deployments require no changes

**Migration Timeline:**

| Deployment Type | Required Action | Timeline |
|----------------|-----------------|----------|
| **Existing deployments** | None - continue using env vars | Immediate |
| **New deployments** | Can adopt `${$secret.*}` syntax | Immediate |
| **Future** | Hardcoded secrets deprecated (lint warning) | 6+ months |

**Deprecation Strategy:**

1. **Phase 1-3 (MVP):** `${$secret.*}` syntax available, hardcoded secrets still valid
2. **3 months:** Document best practices, recommend `${$secret.*}` for new code
3. **6 months:** Add optional lint warning for hardcoded secrets
4. **12 months:** Consider making `${$secret.*}` required (breaking change, major version)

---

## Future Enhancements

### Issue: Add `use.secrets` Declaration to YAML Schema

**Motivation:**

- **Fail-fast validation** - Verify all secrets exist at Case definition load time (before runtime)
- **Audit/compliance** - Explicit list of dependencies for security review
- **Least privilege** - Orchestrator (K8s) can mount only declared secrets
- **Documentation** - Clear which secrets required without parsing YAML body

**Proposed Syntax:**

```yaml
dsl: "0.1.0"
namespace: example
name: sentiment-analysis
version: "1.0.0"

use:
  secrets:
    - openai      # ✅ Validates at load time
    - anthropic   # ✅ Fails if not available

spec:
  workers:
    - name: analyzer
      agent:
        model:
          openai:
            apiKey: "${$secret.openai.apiKey}"  # Must be in use.secrets
```

**Implementation Plan:**

1. **Schema changes**:
   - Add optional `use.secrets` field to CaseDefinition schema
   - Array of secret names

2. **Validation logic** (`CaseDefinitionRegistry.register()`):
   ```java
   if (definition.getUse() != null && definition.getUse().getSecrets() != null) {
     for (String secretName : definition.getUse().getSecrets()) {
       try {
         secretManager.secret(secretName); // Validate exists
       } catch (SecretNotFoundException e) {
         throw new CaseDefinitionValidationException(
             "Secret declared in use.secrets not found: " + secretName, e);
       }
     }
   }
   ```

3. **Enforcement check** (optional strict mode):
   - Parse JQ expressions in YAML
   - Extract all `$secret.X.*` references
   - Verify all `X` are in `use.secrets`
   - Configurable: `casehub.config.secrets.enforce-declaration=true`

4. **Documentation**:
   - Update YAML schema docs
   - Migration guide (optional → recommended → required)
   - Best practices

**Configuration:**

```properties
# Enforce that all $secret references must be declared in use.secrets
# Default: false (optional declaration)
casehub.config.secrets.enforce-declaration=false

# Fail-fast validation at load time (check declared secrets exist)
# Default: true (recommended for production)
casehub.config.secrets.validate-at-load=true
```

**Acceptance Criteria:**

- ✅ `use.secrets` optional field in schema
- ✅ Validation logic implemented
- ✅ Tests for validation (success and failure cases)
- ✅ Documentation updated
- ✅ Migration guide published

**Estimated Effort:** Medium (3-5 days)

**Priority:** High (compliance and fail-fast critical for production)

---

### Issue: Kubernetes SecretManager Implementation

**Motivation:** Production deployments on K8s need native Secrets integration.

**Module:** `casehub-secrets-kubernetes` (new optional module)

**Dependencies:**

```xml
<dependency>
  <groupId>io.kubernetes</groupId>
  <artifactId>client-java</artifactId>
  <version>18.0.0</version>
</dependency>
```

**Implementation:**

```java
package io.casehub.secrets.kubernetes;

import io.casehub.engine.internal.config.SecretManager;
import io.casehub.engine.internal.config.SecretNotFoundException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * SecretManager implementation that reads from Kubernetes Secrets API.
 * 
 * <p>Configuration:
 * <pre>
 * casehub.secrets.kubernetes.namespace=casehub-prod
 * </pre>
 */
@ApplicationScoped
@Alternative
public class KubernetesSecretManager implements SecretManager {

  @Inject
  CoreV1Api k8sApi;

  @ConfigProperty(name = "casehub.secrets.kubernetes.namespace")
  String namespace;

  @Override
  public Map<String, Object> secret(String secretName) {
    try {
      V1Secret k8sSecret = k8sApi.readNamespacedSecret(secretName, namespace, null);
      return decodeSecretData(k8sSecret.getData());
    } catch (Exception e) {
      throw new SecretNotFoundException(secretName);
    }
  }

  private Map<String, Object> decodeSecretData(Map<String, byte[]> data) {
    Map<String, Object> result = new HashMap<>();
    if (data != null) {
      data.forEach((key, value) -> {
        String decoded = new String(Base64.getDecoder().decode(value));
        result.put(key, decoded);
      });
    }
    return result;
  }
}
```

**Configuration:**

```properties
# Enable Kubernetes SecretManager
casehub.secrets.provider=kubernetes

# K8s namespace where secrets are stored
casehub.secrets.kubernetes.namespace=casehub-prod

# Optional: K8s API endpoint (default: in-cluster config)
casehub.secrets.kubernetes.api-server=https://kubernetes.default.svc
```

**K8s Secret Example:**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: openai
  namespace: casehub-prod
type: Opaque
data:
  apiKey: c2stcHJvai1hYmMxMjM=  # base64("sk-proj-abc123")
  organizationId: b3JnLXh5eg==  # base64("org-xyz")
```

**Activation:**

```xml
<!-- Add to pom.xml to activate K8s SecretManager -->
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-secrets-kubernetes</artifactId>
  <version>${project.version}</version>
</dependency>
```

**Acceptance Criteria:**

- ✅ New module `casehub-secrets-kubernetes`
- ✅ K8s client integration
- ✅ RBAC configuration documented
- ✅ Integration tests with k3s/kind
- ✅ Documentation for K8s deployment

**Estimated Effort:** Medium (5-7 days)

**Priority:** High (production requirement)

---

### Issue: HashiCorp Vault SecretManager Implementation

**Motivation:** Enterprise deployments need centralized secret management with rotation.

**Module:** `casehub-secrets-vault` (new optional module)

**Dependencies:**

```xml
<dependency>
  <groupId>com.bettercloud</groupId>
  <artifactId>vault-java-driver</artifactId>
  <version>5.1.0</version>
</dependency>
```

**Implementation:**

```java
package io.casehub.secrets.vault;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.response.LogicalResponse;
import io.casehub.engine.internal.config.SecretManager;
import io.casehub.engine.internal.config.SecretNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;

/**
 * SecretManager implementation that reads from HashiCorp Vault.
 * 
 * <p>Configuration:
 * <pre>
 * casehub.secrets.vault.address=https://vault.example.com
 * casehub.secrets.vault.token=${VAULT_TOKEN}
 * casehub.secrets.vault.path-prefix=secret/data/
 * </pre>
 */
@ApplicationScoped
@Alternative
public class VaultSecretManager implements SecretManager {

  @ConfigProperty(name = "casehub.secrets.vault.address")
  String vaultAddress;

  @ConfigProperty(name = "casehub.secrets.vault.token")
  String vaultToken;

  @ConfigProperty(name = "casehub.secrets.vault.path-prefix", defaultValue = "secret/data/")
  String pathPrefix;

  private Vault vault;

  @PostConstruct
  void init() throws VaultException {
    VaultConfig config = new VaultConfig()
        .address(vaultAddress)
        .token(vaultToken)
        .build();
    this.vault = new Vault(config);
  }

  @Override
  public Map<String, Object> secret(String secretName) {
    try {
      LogicalResponse response = vault.logical()
          .read(pathPrefix + secretName);
      
      Map<String, String> data = response.getData();
      if (data == null || data.isEmpty()) {
        throw new SecretNotFoundException(secretName);
      }
      
      return new HashMap<>(data); // Convert to Map<String, Object>
    } catch (VaultException e) {
      throw new SecretNotFoundException(secretName);
    }
  }
}
```

**Configuration:**

```properties
# Enable Vault SecretManager
casehub.secrets.provider=vault

# Vault address
casehub.secrets.vault.address=https://vault.example.com

# Vault token (use AppRole in production)
casehub.secrets.vault.token=${VAULT_TOKEN}

# KV path prefix
casehub.secrets.vault.path-prefix=secret/data/

# Optional: AppRole authentication
casehub.secrets.vault.auth.approle.role-id=${VAULT_ROLE_ID}
casehub.secrets.vault.auth.approle.secret-id=${VAULT_SECRET_ID}
```

**Vault Secret Example:**

```bash
vault kv put secret/openai \
  apiKey=sk-proj-abc123 \
  organizationId=org-xyz
```

**Acceptance Criteria:**

- ✅ New module `casehub-secrets-vault`
- ✅ Vault client integration
- ✅ Support for AppRole auth
- ✅ Integration tests with Vault dev server
- ✅ Documentation for Vault deployment

**Estimated Effort:** Medium (5-7 days)

**Priority:** Medium (enterprise use case)

---

### Issue: Composite SecretManager (Fallback Chain)

**Motivation:** Support multiple secret backends with fallback (K8s → Vault → System).

**Implementation:**

```java
package io.casehub.engine.internal.config.impl;

import io.casehub.engine.internal.config.SecretManager;
import io.casehub.engine.internal.config.SecretNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;

/**
 * SecretManager that tries multiple delegates in order until one succeeds.
 * 
 * <p>Configuration:
 * <pre>
 * casehub.secrets.composite.order=kubernetes,vault,system
 * </pre>
 */
@ApplicationScoped
@Alternative
public class CompositeSecretManager implements SecretManager {

  private final List<SecretManager> delegates;

  public CompositeSecretManager(List<SecretManager> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public Map<String, Object> secret(String secretName) {
    for (SecretManager delegate : delegates) {
      try {
        return delegate.secret(secretName);
      } catch (SecretNotFoundException e) {
        // Try next delegate
      }
    }
    
    throw new SecretNotFoundException(secretName);
  }
}
```

**Configuration:**

```properties
# Enable composite mode
casehub.secrets.provider=composite

# Order of resolution (first match wins)
casehub.secrets.composite.order=kubernetes,vault,system
```

**Acceptance Criteria:**

- ✅ Composite implementation
- ✅ Configurable fallback order
- ✅ Tests for fallback scenarios
- ✅ Documentation

**Estimated Effort:** Small (2-3 days)

**Priority:** Low (nice-to-have)

---

## Acceptance Criteria

### Phase 1: Infrastructure Foundation

- [ ] All interfaces defined in `common/src/main/java/io/casehub/engine/internal/config/`
- [ ] All implementations in `runtime/src/main/java/io/casehub/engine/internal/config/impl/`
- [ ] Decorators implemented in `runtime/src/main/java/io/casehub/engine/internal/config/decorator/`
- [ ] Unit tests passing with >90% coverage
- [ ] Contract tests passing with 100% coverage
- [ ] CDI beans injectable (`@Inject ConfigContext configContext`)
- [ ] Documentation: JavaDoc on all public interfaces

### Phase 2: ObjectMapper Centralization

- [ ] `CaseHubObjectMapperProducer` produces singleton `@ApplicationScoped ObjectMapper`
- [ ] `JqScopeInjector` injects `$secret` function into JQ scope
- [ ] All manual ObjectMapper creation replaced with `@Inject ObjectMapper`
- [ ] Integration tests verify JQ `$secret` resolution works
- [ ] Documentation explains JQ integration points

### Phase 3: YAML Schema Updates

- [ ] YAML schema documents `${$secret.*}` syntax with examples
- [ ] Test fixtures use `${$secret.*}` syntax
- [ ] `docs/config-secrets-management.md` published (complete guide)
- [ ] `docs/secret-manager-spi.md` updated (mark as implemented)
- [ ] Migration guide available
- [ ] Examples in README

### Phase 4: ChatModelProvider Refactoring (Optional)

- [ ] All ChatModelProviders use ConfigManager programmatically
- [ ] Fallback to `System.getenv()` works
- [ ] No breaking changes for existing deployments
- [ ] Tests verify both ConfigManager and fallback paths

### Cross-Cutting Concerns

- [ ] **Security**: Secrets never logged (values masked in all outputs)
- [ ] **Security**: Error messages don't leak secret values
- [ ] **Compliance**: Audit logging enabled by default
- [ ] **Compliance**: Audit logs track secret access (name, caller, timestamp)
- [ ] **Performance**: Caching configurable for external stores
- [ ] **Backward Compatibility**: Existing code continues to work
- [ ] **Testing**: All error paths tested (SecretNotFoundException, etc.)
- [ ] **Documentation**: Complete guide published

---

## Related Issues & References

### Issues

- **#247** - Implement SecretManager SPI for K8s/Vault integration (this design addresses MVP)
- **TBD** - Add `use.secrets` declaration to YAML schema (future enhancement)
- **TBD** - Kubernetes SecretManager implementation (future module)
- **TBD** - HashiCorp Vault SecretManager implementation (future module)

### References

**Serverless Workflow SDK:**
- `io.serverlessworkflow.impl.config.ConfigManager` (7.13.4.Final)
- `io.serverlessworkflow.impl.config.SecretManager` (7.13.4.Final)
- `io.serverlessworkflow.impl.config.ConfigSecretManager` (7.13.4.Final)
- Maven: `~/.m2/repository/io/serverlessworkflow/serverlessworkflow-impl-core/7.13.4.Final/`

**Platform Documentation:**
- `https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md`
- `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-engine.md`

**Existing Documentation:**
- `docs/secret-manager-spi.md` (proposal, to be updated)
- `CLAUDE.md` (project instructions)

**Code References:**
- `api/src/main/java/io/casehub/api/spi/WorkerProvisioner.java` (SPI pattern example)
- `api/src/main/java/io/casehub/api/model/ai/openai/OpenAiChatModelProvider.java` (current System.getenv usage)
- `schema/src/main/resources/schema/CaseDefinition.yaml` (YAML schema)

---

## Summary

This design introduces a comprehensive Config & Secrets management system for casehub-engine:

**Core Components:**
- `ConfigManager` wraps Quarkus MicroProfile Config
- `SecretManager` builds structured secrets from config properties
- `ConfigContext` provides centralized access
- Decorators add audit logging and caching

**JQ Integration:**
- Centralized `@ApplicationScoped ObjectMapper` bean
- `JqScopeInjector` adds `$secret` function to JQ scope
- YAML expressions `${$secret.openai.apiKey}` resolve at runtime

**Key Principles:**
- Adapter pattern wraps Serverless Workflow
- Decorator pattern for compliance and performance
- Fail-fast error handling
- Security-first (never log secret values)
- Backward compatible (no breaking changes)

**MVP Scope:**
- Phases 1-3 (Infrastructure, ObjectMapper, YAML Schema)
- System properties / Quarkus Config backend
- Future: K8s Secrets, Vault, `use.secrets` declaration

**Next Steps:**
1. Create GitHub issues for each phase
2. Begin Phase 1 implementation
3. Review and iterate based on feedback
