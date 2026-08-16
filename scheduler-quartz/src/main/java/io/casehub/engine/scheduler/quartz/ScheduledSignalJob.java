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
package io.casehub.engine.scheduler.quartz;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.ContextSignalEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@DisallowConcurrentExecution
@ApplicationScoped
public class ScheduledSignalJob implements Job {

  private static final Logger LOG = Logger.getLogger(ScheduledSignalJob.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject EventBus eventBus;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    JobDataMap data = context.getJobDetail().getJobDataMap();
    String caseIdStr = data.getString("caseId");
    String bindingName = data.getString("bindingName");
    String signalPayloadJson = data.getString("signalPayload");

    UUID caseId = UUID.fromString(caseIdStr);
    LOG.infof("Signal trigger fired: case=%s, binding=%s", caseId, bindingName);

    CaseInstance caseInstance;
    try {
      caseInstance = recoveryService.loadOrRestoreCaseInstance(caseId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to load case %s, skipping signal trigger", caseId);
      return;
    }

    if (caseInstance.getState() != CaseStatus.RUNNING) {
      LOG.infof("Case %s is %s, skipping signal trigger", caseId, caseInstance.getState());
      return;
    }

    if ("true".equals(data.getString("hasCondition"))) {
      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
      if (definition == null) {
        LOG.warnf("CaseDefinition not found for case %s", caseId);
        return;
      }
      Binding binding =
          definition.getBindings().stream()
              .filter(b -> bindingName.equals(b.getName()))
              .findFirst()
              .orElse(null);
      if (binding == null || binding.getWhen() == null) {
        LOG.warnf("Binding '%s' not found or has no condition", bindingName);
        return;
      }
      try {
        boolean conditionMet =
            expressionEngineRegistry.evaluate(binding.getWhen(), caseInstance.getCaseContext());
        if (!conditionMet) {
          LOG.debugf("Signal condition not met for case=%s binding=%s", caseId, bindingName);
          return;
        }
      } catch (Exception e) {
        LOG.warnf(e, "Condition evaluation failed for case=%s binding=%s", caseId, bindingName);
        return;
      }
    }

    try {
      Map<String, Object> payload =
          OBJECT_MAPPER.readValue(signalPayloadJson, new TypeReference<>() {});
      eventBus.publish(
          EventBusAddresses.CONTEXT_SIGNAL,
          new ContextSignalEvent(caseInstance, bindingName, payload));
    } catch (Exception e) {
      throw new JobExecutionException("Failed to parse signal payload", e);
    }
  }
}
