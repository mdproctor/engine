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
package io.casehub.engine.internal.marshaller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injects CDI-managed ObjectMapper into CaseDefinitionYamlMapper at startup.
 *
 * <p>Observes {@link StartupEvent} and calls {@link CaseDefinitionYamlMapper#setObjectMapper} to
 * replace the default ObjectMapper with the centralized CDI bean from {@link
 * CaseHubObjectMapperProducer}.
 */
@ApplicationScoped
public class ObjectMapperInjector {

  private static final Logger LOG = LoggerFactory.getLogger(ObjectMapperInjector.class);

  @Inject @YamlMapper ObjectMapper yamlMapper;

  // TODO this workaround must be fixed
  void onStartup(@Observes StartupEvent event) {
    CaseDefinitionYamlMapper.setObjectMapper(yamlMapper);
    LOG.info("Injected CDI ObjectMapper into CaseDefinitionYamlMapper");
  }
}
