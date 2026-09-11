package io.casehub.engine.react.quarkus;

import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.engine.react.ReActCycleEventHandler;
import io.casehub.engine.react.ReActWorkerFunctionHandler;
import io.casehub.engine.react.ReActWorkerFunctionProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;

@ApplicationScoped
public class ReactBeans {

  @Produces
  @ApplicationScoped
  ReActCycleEventHandler reActCycleEventHandler(EventLogRepository eventLogRepository) {
    return new ReActCycleEventHandler(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  ReActWorkerFunctionProvider reActWorkerFunctionProvider() {
    return new ReActWorkerFunctionProvider();
  }

  @Produces
  @ApplicationScoped
  ReActWorkerFunctionHandler reActWorkerFunctionHandler(
      WorkerRuntimeFactory runtimeFactory,
      EventDispatcher eventDispatcher,
      @io.quarkus.virtual.threads.VirtualThreads ExecutorService executor) {
    return new ReActWorkerFunctionHandler(runtimeFactory, eventDispatcher, executor);
  }
}
