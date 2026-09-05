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
package io.casehub.engine.internal.recovery;

import io.casehub.api.model.StallRecoveryAction;
import io.casehub.api.spi.recovery.StallClassificationContext;
import io.casehub.api.spi.recovery.StallClassifier;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
public class DefaultStallClassifier implements StallClassifier {

  private static final Logger LOG = Logger.getLogger(DefaultStallClassifier.class);

  @Override
  public StallRecoveryAction classify(StallClassificationContext context) {
    StallRecoveryAction action =
        context
            .policy()
            .conditionActions()
            .getOrDefault(
                context.recoveryContext().conditionType(), context.policy().defaultAction());

    if (action.requiresBinding() && context.recoveryContext().resolvedBindingName() == null) {
      LOG.warnf(
          "Stall action %s requires binding resolution but none available for %s — downgrading to NOTIFY",
          action, context.recoveryContext().conditionType());
      return StallRecoveryAction.NOTIFY;
    }
    return action;
  }

  @Override
  public String id() {
    return "policy-lookup";
  }
}
