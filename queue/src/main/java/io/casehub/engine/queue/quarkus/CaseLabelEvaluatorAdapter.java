package io.casehub.engine.queue.quarkus;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.queue.label.CaseLabelEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class CaseLabelEvaluatorAdapter {

  @Inject CaseLabelEvaluator evaluator;

  void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
    evaluator.onCaseLifecycle(event);
  }
}
