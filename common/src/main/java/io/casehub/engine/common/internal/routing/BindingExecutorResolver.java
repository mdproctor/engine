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
package io.casehub.engine.common.internal.routing;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.worker.api.Worker;
import java.util.List;

public final class BindingExecutorResolver {

  private BindingExecutorResolver() {}

  public static ExecutorRef resolve(Binding binding, CaseDefinition definition) {
    return switch (binding.target()) {
      case null -> ExecutorRef.of("unknown");
      case CapabilityTarget ct -> {
        String capName = ct.capability().name();
        List<Worker> matching =
            definition.getWorkers().stream()
                .filter(w -> w.capabilityNames() != null && w.capabilityNames().contains(capName))
                .toList();
        yield matching.isEmpty()
            ? ExecutorRef.of(capName)
            : ExecutorRef.fromWorker(matching.get(0));
      }
      case SubCaseTarget st -> ExecutorRef.of("unknown");
      case HumanTaskTarget ht -> ExecutorRef.of("unknown");
      case ExtensionTarget et -> ExecutorRef.of("unknown");
    };
  }
}
