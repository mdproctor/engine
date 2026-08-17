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
package io.casehub.engine.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Effect;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Param;
import io.casehub.engine.annotations.PlanningMode;
import io.casehub.engine.annotations.SoftDependency;
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AnnotationFeaturesTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      FeatureCase.class,
                      FeatureCase.InputData.class,
                      FeatureCase.OutputData.class));

  @Case(namespace = "test", name = "Features", version = "1.0.0", planning = PlanningMode.GOAP)
  public interface FeatureCase {

    @Worker(value = "customName", cost = 0.3)
    @Bind(contextChange = ".ready")
    @Bind(cron = "0 0 * * *")
    default OutputData doWork(String input, @SoftDependency InputData soft) {
      return new OutputData("done");
    }

    @Worker(capability = "transform", cost = 0.5)
    @Bind(contextChange = ".inputData != null", when = ".priority == 'high'")
    @Effect("transformedResult")
    default OutputData transform(InputData data, @Param("config") String config) {
      return new OutputData("transformed");
    }

    @Goal(value = "Work complete", condition = ".outputData != null")
    default void done() {}

    record InputData(String value) {}

    record OutputData(String result) {}
  }

  @Inject CaseDefinition definition;

  @Test
  void repeatable_bind_produces_multiple_bindings() {
    long doWorkBindings =
        definition.getBindings().stream().filter(b -> b.getName().equals("doWork")).count();
    assertThat(doWorkBindings).isEqualTo(2);
  }

  @Test
  void worker_value_overrides_capability_name() {
    assertThat(definition.getWorkers().stream().anyMatch(w -> w.name().equals("doWork"))).isTrue();
    assertThat(
            definition.getWorkers().stream()
                .filter(w -> w.name().equals("doWork"))
                .findFirst()
                .get()
                .capabilityNames())
        .contains("customName");
  }

  @Test
  void effect_annotation_overrides_key() {
    var transformAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("transform")).findFirst();
    assertThat(transformAction).isPresent();
    assertThat(transformAction.get().effects()).containsKey("transformedResult");
    assertThat(transformAction.get().effects()).doesNotContainKey("outputData");
  }

  @Test
  void soft_dependency_in_goap() {
    var doWorkAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("doWork")).findFirst();
    assertThat(doWorkAction).isPresent();
    assertThat(doWorkAction.get().softPreconditions()).containsKey("inputData");
    assertThat(doWorkAction.get().preconditions()).doesNotContainKey("inputData");
  }

  @Test
  void param_excluded_from_goap_inference() {
    var transformAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("transform")).findFirst();
    assertThat(transformAction).isPresent();
    assertThat(transformAction.get().preconditions()).containsKey("inputData");
    assertThat(transformAction.get().preconditions()).doesNotContainKey("config");
    assertThat(transformAction.get().preconditions()).doesNotContainKey("string");
  }

  @Test
  void bind_with_when_guard() {
    var transformBinding =
        definition.getBindings().stream().filter(b -> b.getName().equals("transform")).findFirst();
    assertThat(transformBinding).isPresent();
    assertThat(transformBinding.get().getWhen()).isNotNull();
  }

  @Test
  void bind_with_cron_trigger() {
    var cronBindings =
        definition.getBindings().stream()
            .filter(b -> b.getName().equals("doWork"))
            .filter(b -> b.getOn() instanceof ScheduleTrigger)
            .toList();
    assertThat(cronBindings).hasSize(1);
  }
}
