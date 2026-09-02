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
package io.casehub.api.spi.judgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.StaticSetStrategy;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallerConfigTest {

  @Test
  void humanAllFields() {
    var groups = new CandidateSetSpec.Inline(StaticSetStrategy.of("managers"));
    var users = new CandidateSetSpec.Inline(StaticSetStrategy.of("user-1"));
    var human =
        new CallerConfig.Human(
            groups,
            users,
            "Review needed",
            null,
            Set.of("APPROVE", "REJECT"),
            24,
            "case",
            null,
            "high",
            "tmpl-1",
            String.class,
            QuorumConfig.majority(3));
    assertEquals(groups, human.candidateGroups());
    assertEquals(users, human.candidateUsers());
    assertEquals("Review needed", human.title());
    assertEquals(Set.of("APPROVE", "REJECT"), human.outcomes());
    assertEquals(24, human.claimDeadlineHours());
    assertEquals("high", human.priority());
    assertEquals("tmpl-1", human.templateRef());
    assertEquals(String.class, human.payloadType());
    assertNotNull(human.quorum());
  }

  @Test
  void humanConvenienceFactory() {
    var human = CallerConfig.human();
    assertNull(human.candidateGroups());
    assertNull(human.candidateUsers());
    assertNull(human.title());
    assertNull(human.outcomes());
    assertNull(human.quorum());
  }

  @Test
  void humanOutcomesDefensiveCopy() {
    var mutable = new java.util.HashSet<>(Set.of("A", "B"));
    var human =
        new CallerConfig.Human(
            null, null, null, null, mutable, null, null, null, null, null, null, null);
    mutable.add("C");
    assertEquals(2, human.outcomes().size());
  }

  @Test
  void llmAllFields() {
    var llm = new CallerConfig.Llm("anthropic", "claude-sonnet-4-20250514", "You are a judge.");
    assertEquals("anthropic", llm.modelId());
    assertEquals("claude-sonnet-4-20250514", llm.modelName());
    assertEquals("You are a judge.", llm.systemPrompt());
  }

  @Test
  void llmNoArgs() {
    var llm = new CallerConfig.Llm();
    assertNull(llm.modelId());
    assertNull(llm.modelName());
    assertNull(llm.systemPrompt());
  }

  @Test
  void a2aWithStreaming() {
    var a2a = new CallerConfig.A2A("https://agent.example.com", "review", true);
    assertTrue(a2a.streaming());
  }

  @Test
  void a2aConvenienceConstructors() {
    var a2a1 = new CallerConfig.A2A("https://agent.example.com");
    assertNull(a2a1.skill());
    assertFalse(a2a1.streaming());

    var a2a2 = new CallerConfig.A2A("https://agent.example.com", "review");
    assertEquals("review", a2a2.skill());
    assertFalse(a2a2.streaming());
  }

  @Test
  void anyConfig() {
    var any = new CallerConfig.Any();
    assertInstanceOf(CallerConfig.class, any);
  }

  @Test
  void sealedTypeExhaustiveness() {
    CallerConfig config = CallerConfig.human();
    String result =
        switch (config) {
          case CallerConfig.Human h ->
              "human:" + (h.candidateGroups() == null ? "default" : "custom");
          case CallerConfig.Llm l -> "llm:" + l.modelId();
          case CallerConfig.A2A a -> "a2a:" + a.endpoint();
          case CallerConfig.Any a -> "any";
        };
    assertEquals("human:default", result);
  }
}
