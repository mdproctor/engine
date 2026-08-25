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
package io.casehub.api.model.converter;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.SubCaseMapping;
import io.casehub.api.model.Trigger;
import io.casehub.api.model.converter.deser.BindingDeserializer;
import io.casehub.api.model.converter.deser.CaseCompletionDeserializer;
import io.casehub.api.model.converter.deser.CaseDefinitionDeserializer;
import io.casehub.api.model.converter.deser.ExpressionEvaluatorDeserializer;
import io.casehub.api.model.converter.deser.GoalExpressionDeserializer;
import io.casehub.api.model.converter.deser.SubCaseMappingDeserializer;
import io.casehub.api.model.converter.deser.TriggerDeserializer;
import io.casehub.api.model.converter.deser.WorkerDeserializer;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Worker;

public class CaseDefinitionModule extends SimpleModule {

  public CaseDefinitionModule(ExpressionEngineRegistry registry) {
    super("CaseDefinitionModule");
    addDeserializer(ExpressionEvaluator.class, new ExpressionEvaluatorDeserializer(registry));
    addDeserializer(GoalExpression.class, new GoalExpressionDeserializer());
    addDeserializer(CaseCompletion.class, new CaseCompletionDeserializer());
    addDeserializer(Trigger.class, new TriggerDeserializer());
    addDeserializer(SubCaseMapping.class, new SubCaseMappingDeserializer());
    addDeserializer(Worker.class, new WorkerDeserializer());
    addDeserializer(Binding.class, new BindingDeserializer());
    addDeserializer(CaseDefinition.class, new CaseDefinitionDeserializer());
  }

  @Override
  public void setupModule(SetupContext context) {
    super.setupModule(context);
    context.setMixInAnnotations(
        io.casehub.api.model.CaseDefinitionSpec.class,
        io.casehub.api.model.converter.deser.CaseDefinitionSpecMixin.class);
    context.setMixInAnnotations(
        io.casehub.api.model.CaseDefinition.class,
        io.casehub.api.model.converter.deser.CaseDefinitionMixin.class);
    context.setMixInAnnotations(
        io.casehub.engine.plan.goap.GoapAction.class,
        io.casehub.api.model.converter.deser.GoapActionMixin.class);
  }
}
