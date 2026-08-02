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
package io.casehub.engine.internal.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScopedWorkerOutputIntegrationTest {

  @Inject ScopedWorkerOutputHandler handler;

  @Test
  void scopedWorkerOutputAppearsInContext() {
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("ns");
    metaModel.setName("test");
    metaModel.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl());
    instance.setState(CaseStatus.RUNNING);
    instance.tenancyId = "test-tenant";

    var event =
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("result", "done"), "binding1", null);

    handler.onScopedWorkerOutput(event);

    assertEquals("done", instance.getCaseContext().get("result"));
  }

  @Test
  void multipleOutputsAccumulate() {
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("ns");
    metaModel.setName("test");
    metaModel.setVersion("1.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl());
    instance.setState(CaseStatus.RUNNING);
    instance.tenancyId = "test-tenant";

    handler.onScopedWorkerOutput(
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("step1", "done"), "binding1", null));
    handler.onScopedWorkerOutput(
        new ScopedWorkerOutputEvent(
            instance, "worker1", Map.of("step2", "done"), "binding1", null));

    assertEquals("done", instance.getCaseContext().get("step1"));
    assertEquals("done", instance.getCaseContext().get("step2"));
  }
}
