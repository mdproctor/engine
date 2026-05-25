# Worker Execution Timeout

Workers can hang or run indefinitely. To prevent this, Case Hub supports configurable execution timeouts at both global and per-worker levels.

## Configuration

### Global Default Timeout

Set the default timeout for all workers in `application.properties`:

```properties
# Default timeout for worker execution (in milliseconds)
casehub.engine.worker.default-timeout-ms=60000
```

**Default value:** 60000ms (60 seconds)

### Per-Worker Timeout Override

Individual workers can override the default timeout via `ExecutionPolicy`:

```java
Worker worker = Worker.builder()
    .name("slow-worker")
    .capabilities(capability)
    .function(ctx -> {
        // Long-running work
        return Map.of("result", "done");
    })
    .executionPolicy(new ExecutionPolicy(
        120000,  // 120 seconds timeout for this specific worker
        new RetryPolicy()
    ))
    .build();
```

**YAML/JSON example:**

```yaml
workers:
  - name: slow-worker
    executionPolicy:
      timeoutMs: 120000  # Override default timeout
      retries:
        maxAttempts: 3
    capabilities:
      - processLargeFile
    function: ...
```

## Behavior

- When `timeoutMs` is **null** or **not specified**: uses the global default from `casehub.engine.worker.default-timeout-ms`
- When `timeoutMs` is **specified**: uses the worker-specific value, overriding the global default
- On **timeout**: worker execution fails with `JobExecutionException` containing a `TimeoutException` cause
- **Retry logic**: if `ExecutionPolicy.retries` is configured, the worker will be retried according to the retry policy

## Timeout Exception Handling

When a worker times out:

1. A `JobExecutionException` is thrown with message: `"Worker execution timed out after {timeout}ms: {workerName}"`
2. The cause is a `TimeoutException`
3. Retry logic (if configured) will attempt to re-execute the worker
4. After exhausting retries, the case may transition to `FAULTED` state (depending on error handling policy)

## Example: Progressive Timeout Strategy

```java
// Fast workers: 10 seconds
Worker fastWorker = Worker.builder()
    .name("validate-input")
    .executionPolicy(new ExecutionPolicy(10000, new RetryPolicy()))
    .function(ctx -> quickValidation(ctx))
    .build();

// Medium workers: use default (60 seconds)
Worker mediumWorker = Worker.builder()
    .name("process-data")
    .executionPolicy(new ExecutionPolicy())  // uses default timeout
    .function(ctx -> processData(ctx))
    .build();

// Slow workers: 5 minutes
Worker slowWorker = Worker.builder()
    .name("generate-report")
    .executionPolicy(new ExecutionPolicy(300000, new RetryPolicy()))
    .function(ctx -> generateReport(ctx))
    .build();
```

## Monitoring

Timeout events are logged at WARN level:

```
WARN  [QuartzWorkerExecutionJob] Worker execution timed out after 60000ms: slow-worker
```

Monitor these logs to identify workers that consistently hit timeout limits and may need:
- Longer timeout configuration
- Performance optimization
- Breaking into smaller workers

## Best Practices

1. **Set realistic defaults**: Base `default-timeout-ms` on your typical worker execution time
2. **Override judiciously**: Only override timeout for workers with genuinely different performance characteristics
3. **Monitor and adjust**: Track timeout occurrences and adjust timeouts based on actual execution patterns
4. **Consider retry policy**: Workers with longer timeouts may benefit from fewer retries to avoid cascading delays
5. **Break up long operations**: If a worker consistently needs very long timeouts (>5 minutes), consider breaking it into smaller workers

## Implementation Notes

- **Workflow workers**: Timeout applies to the entire serverless workflow execution
- **Function workers**: Timeout applies to the Java function execution
- **Agent workers**: Timeout applies to the entire LLM round-trip, including network latency and model inference time. The agent function runs inside `CompletableFuture.supplyAsync()` with `orTimeout(timeoutMs, MILLISECONDS)`. `TimeoutException` triggers the standard worker retry/stall mechanism.
- **Thread safety**: Function workers execute in a separate thread pool to enable timeout enforcement
- **Interruption**: When timeout occurs, the worker thread receives an interrupt signal (but may not immediately stop if the code doesn't check interruption status)
