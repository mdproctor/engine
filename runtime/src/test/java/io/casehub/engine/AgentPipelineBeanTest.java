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
package io.casehub.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AgentPipelineBeanTest {

  @Inject AgentPipelineBean bean;

  @Inject CaseInstanceCache caseInstanceCache;

  MockWebServer mockServer;

  @InjectMock Agents.SentimentAnalysisAgent sentimentMock;

  @InjectMock Agents.ContentSummarizerAgent summarizerMock;

  @BeforeEach
  void setUp() throws IOException {
    mockServer = new MockWebServer();
    mockServer.start(8889);

    when(sentimentMock.analyze(anyString(), any(Agents.SentimentRequest.class)))
        .thenAnswer(
            invocation ->
                new Agents.SentimentResult(
                    "positive", 0.92, List.of("innovative", "growth", "promising")));

    when(summarizerMock.summarize(anyString(), any(Agents.SummaryRequest.class)))
        .thenAnswer(
            invocation ->
                new Agents.SummaryResult(
                    "The document describes an innovative approach.",
                    List.of("Innovation driven", "High growth potential")));

    bean.setAgents(sentimentMock, summarizerMock);
  }

  @AfterEach
  void tearDown() throws IOException {
    mockServer.shutdown();
  }

  @Test
  public void testAgentPipeline() {
    mockServer.enqueue(
        new MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(
                "{\"content\":\"Our new AI platform leverages cutting-edge technology.\",\"source\":\"api\"}"));

    Map<String, Object> initialContext =
        Map.of(
            "documentId", "doc-agent-1",
            "step", "submitted");

    AtomicReference<UUID> ref = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    bean.startCase(initialContext)
        .thenAccept(ref::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertNotNull(ref.get());
            });

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(ref.get());
              assertNotNull(instance);
              assertEquals(CaseStatus.COMPLETED, instance.getState());
            });

    assertEquals(1, mockServer.getRequestCount());

    verify(sentimentMock).analyze(anyString(), any(Agents.SentimentRequest.class));
    verify(summarizerMock).summarize(anyString(), any(Agents.SummaryRequest.class));
  }
}
