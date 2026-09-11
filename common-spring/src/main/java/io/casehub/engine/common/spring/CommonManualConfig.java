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
package io.casehub.engine.common.spring;

import io.casehub.api.context.ContextBridge;
import io.casehub.api.spi.DataRefResolver;
import io.casehub.engine.common.internal.channel.InMemoryDataChannelFactory;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.context.DataRefRegistry;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.spi.JudgmentScheduler;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CommonManualConfig {

  @Bean
  @ConditionalOnMissingBean
  public InMemoryDataChannelFactory inMemoryDataChannelFactory(
      @Value("${casehub.engine.channel.send-timeout-ms:0}") long sendTimeoutMs) {
    return new InMemoryDataChannelFactory(sendTimeoutMs);
  }

  @Bean
  public WorkerExecutionConfig workerExecutionConfig(
      @Value("${casehub.engine.worker.default-timeout-ms:60000}") int defaultTimeoutMs) {
    return new WorkerExecutionConfig(defaultTimeoutMs);
  }

  @Bean
  public DataRefRegistry dataRefRegistry(List<DataRefResolver> resolvers) {
    return new DataRefRegistry(resolvers);
  }

  @Bean
  public BridgeResolver bridgeResolver(
      List<ContextBridge<?>> bridges, DataRefRegistry dataRefRegistry) {
    return new BridgeResolver(bridges, dataRefRegistry);
  }

  @Bean
  public JudgmentNodeExecutor judgmentNodeExecutor(Optional<JudgmentScheduler> scheduler) {
    return new JudgmentNodeExecutor(scheduler);
  }
}
