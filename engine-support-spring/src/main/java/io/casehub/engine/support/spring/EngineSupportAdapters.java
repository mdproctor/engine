/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.support.spring;

import io.casehub.connectors.InboundMessage;
import io.casehub.engine.a2a.A2AClientRegistry;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.inbound.InboundSignalBridge;
import io.casehub.engine.mcp.McpClientRegistry;
import io.casehub.engine.queue.entry.CaseQueueEntryManager;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.label.CaseLabelEvaluator;
import io.casehub.engine.queue.reconcile.CaseLabelReconciler;
import io.casehub.engine.react.ReActCycleEventHandler;
import io.casehub.engine.work.cloudevent.WorkIntegrationConflictDetector;
import io.casehub.engine.work.cloudevent.WorkItemLifecycleCloudEventConsumer;
import io.cloudevents.CloudEvent;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

@AutoConfiguration
public class EngineSupportAdapters {

  // --- a2a: ShutdownEvent → @PreDestroy ---

  @Bean
  A2AClientRegistryShutdown a2aClientRegistryShutdown(A2AClientRegistry clientRegistry) {
    return new A2AClientRegistryShutdown(clientRegistry);
  }

  static class A2AClientRegistryShutdown {
    private final A2AClientRegistry clientRegistry;

    A2AClientRegistryShutdown(A2AClientRegistry clientRegistry) {
      this.clientRegistry = clientRegistry;
    }

    @PreDestroy
    public void shutdown() {
      clientRegistry.shutdown();
    }
  }

  // --- mcp: ShutdownEvent → @PreDestroy ---

  @Bean
  McpClientRegistryShutdown mcpClientRegistryShutdown(McpClientRegistry clientRegistry) {
    return new McpClientRegistryShutdown(clientRegistry);
  }

  static class McpClientRegistryShutdown {
    private final McpClientRegistry clientRegistry;

    McpClientRegistryShutdown(McpClientRegistry clientRegistry) {
      this.clientRegistry = clientRegistry;
    }

    @PreDestroy
    public void shutdown() {
      clientRegistry.shutdown();
    }
  }

  // --- inbound: InboundMessage @ObservesAsync → @Async @EventListener ---

  @Bean
  InboundSignalBridgeSpringAdapter inboundSignalBridgeSpringAdapter(InboundSignalBridge bridge) {
    return new InboundSignalBridgeSpringAdapter(bridge);
  }

  static class InboundSignalBridgeSpringAdapter {
    private final InboundSignalBridge bridge;

    InboundSignalBridgeSpringAdapter(InboundSignalBridge bridge) {
      this.bridge = bridge;
    }

    @Async
    @EventListener
    public void onInboundMessage(InboundMessage message) {
      bridge.onInboundMessage(message);
    }
  }

  // --- queue: CaseLifecycleEvent @ObservesAsync → @Async @EventListener ---

  @Bean
  CaseLabelEvaluatorSpringAdapter caseLabelEvaluatorSpringAdapter(CaseLabelEvaluator evaluator) {
    return new CaseLabelEvaluatorSpringAdapter(evaluator);
  }

  static class CaseLabelEvaluatorSpringAdapter {
    private final CaseLabelEvaluator evaluator;

    CaseLabelEvaluatorSpringAdapter(CaseLabelEvaluator evaluator) {
      this.evaluator = evaluator;
    }

    @Async
    @EventListener
    public void onCaseLifecycle(CaseLifecycleEvent event) {
      evaluator.onCaseLifecycle(event);
    }
  }

  // --- queue: StartupEvent → @PostConstruct ---

  @Bean
  CaseLabelReconcilerStartup caseLabelReconcilerStartup(CaseLabelReconciler reconciler) {
    return new CaseLabelReconcilerStartup(reconciler);
  }

  static class CaseLabelReconcilerStartup {
    private final CaseLabelReconciler reconciler;

    CaseLabelReconcilerStartup(CaseLabelReconciler reconciler) {
      this.reconciler = reconciler;
    }

    @PostConstruct
    public void reconcile() {
      reconciler.reconcile();
    }
  }

  // --- queue: CaseQueueEvent @Observes (synchronous) → @EventListener ---

  @Bean
  CaseQueueEntryManagerSpringAdapter caseQueueEntryManagerSpringAdapter(
      CaseQueueEntryManager manager) {
    return new CaseQueueEntryManagerSpringAdapter(manager);
  }

  static class CaseQueueEntryManagerSpringAdapter {
    private final CaseQueueEntryManager manager;

    CaseQueueEntryManagerSpringAdapter(CaseQueueEntryManager manager) {
      this.manager = manager;
    }

    @EventListener
    public void onQueueEvent(CaseQueueEvent event) {
      manager.onQueueEvent(event);
    }
  }

  // --- react: @ConsumeEvent → @EventListener ---

  @Bean
  ReActCycleSpringAdapter reActCycleSpringAdapter(ReActCycleEventHandler handler) {
    return new ReActCycleSpringAdapter(handler);
  }

  static class ReActCycleSpringAdapter {
    private final ReActCycleEventHandler handler;

    ReActCycleSpringAdapter(ReActCycleEventHandler handler) {
      this.handler = handler;
    }

    @Async
    @EventListener
    public void onReactCycle(JsonObject message) {
      handler.onReactCycle(message);
    }
  }

  // --- work-cloudevent: StartupEvent → @PostConstruct ---

  @Bean
  WorkIntegrationConflictDetectorStartup workIntegrationConflictDetectorStartup(
      WorkIntegrationConflictDetector detector) {
    return new WorkIntegrationConflictDetectorStartup(detector);
  }

  static class WorkIntegrationConflictDetectorStartup {
    private final WorkIntegrationConflictDetector detector;

    WorkIntegrationConflictDetectorStartup(WorkIntegrationConflictDetector detector) {
      this.detector = detector;
    }

    @PostConstruct
    public void check() {
      detector.check();
    }
  }

  // --- work-cloudevent: CloudEvent @ObservesAsync → @Async @EventListener ---

  @Bean
  WorkItemLifecycleCloudEventSpringAdapter workItemLifecycleCloudEventSpringAdapter(
      WorkItemLifecycleCloudEventConsumer consumer) {
    return new WorkItemLifecycleCloudEventSpringAdapter(consumer);
  }

  static class WorkItemLifecycleCloudEventSpringAdapter {
    private final WorkItemLifecycleCloudEventConsumer consumer;

    WorkItemLifecycleCloudEventSpringAdapter(WorkItemLifecycleCloudEventConsumer consumer) {
      this.consumer = consumer;
    }

    @Async
    @EventListener
    public void onLifecycleCloudEvent(CloudEvent ce) {
      consumer.onLifecycleCloudEvent(ce);
    }
  }
}
