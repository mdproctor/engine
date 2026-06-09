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
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.engine.common.internal.config.ConfigContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Centralized ObjectMapper CDI producer.
 *
 * <p>Produces a singleton YAML-format ObjectMapper configured with:
 *
 * <ul>
 *   <li>YAMLFactory for YAML parsing
 *   <li>ConfigSecretResolvingDeserializer for ${$secret.*} and ${$config.*} placeholder resolution
 * </ul>
 *
 * <p>Placeholders are resolved once at YAML deserialization time, not at runtime.
 */
@ApplicationScoped
public class CaseHubObjectMapperProducer {

  @Inject ConfigContext configContext;

  @Produces
  @Singleton
  @io.casehub.api.marshaller.YamlMapper
  public ObjectMapper yamlObjectMapper() {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    // Register custom deserializer for config/secret placeholder resolution
    SimpleModule module = new SimpleModule("ConfigSecretResolvingModule");
    module.addDeserializer(String.class, new ConfigSecretResolvingDeserializer(configContext));
    mapper.registerModule(module);

    return mapper;
  }
}
