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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.annotation.Priority;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingPromptAssemblerTest {

  @Test
  void noSections_returnsNull() {
    var assembler = new RoutingPromptAssembler(List.of());
    assertThat(assembler.assemble(context(), candidates())).isNull();
  }

  @Test
  void singleSection_returnsRenderedContent() {
    RoutingPromptSection section = (ctx, eligible) -> "Historical context: 3 cases";
    var assembler = new RoutingPromptAssembler(List.of(section));
    assertThat(assembler.assemble(context(), candidates()))
        .isEqualTo("Historical context: 3 cases");
  }

  @Test
  void sectionReturnsNull_skipped() {
    RoutingPromptSection nullSection = (ctx, eligible) -> null;
    RoutingPromptSection realSection = (ctx, eligible) -> "data";
    var assembler = new RoutingPromptAssembler(List.of(nullSection, realSection));
    assertThat(assembler.assemble(context(), candidates())).isEqualTo("data");
  }

  @Test
  void sectionReturnsBlank_skipped() {
    RoutingPromptSection blankSection = (ctx, eligible) -> "   ";
    RoutingPromptSection realSection = (ctx, eligible) -> "data";
    var assembler = new RoutingPromptAssembler(List.of(blankSection, realSection));
    assertThat(assembler.assemble(context(), candidates())).isEqualTo("data");
  }

  @Test
  void multipleSections_joinedWithDoubleNewline() {
    RoutingPromptSection s1 = (ctx, eligible) -> "section-1";
    RoutingPromptSection s2 = (ctx, eligible) -> "section-2";
    var assembler = new RoutingPromptAssembler(List.of(s1, s2));
    assertThat(assembler.assemble(context(), candidates())).isEqualTo("section-1\n\nsection-2");
  }

  @Test
  void throwingSection_loggedAndSkipped_otherSectionsSurvive() {
    RoutingPromptSection boom =
        (ctx, eligible) -> {
          throw new RuntimeException("fail");
        };
    RoutingPromptSection ok = (ctx, eligible) -> "survived";
    var assembler = new RoutingPromptAssembler(List.of(boom, ok));
    assertThat(assembler.assemble(context(), candidates())).isEqualTo("survived");
  }

  @Test
  void allSectionsReturnNull_returnsNull() {
    RoutingPromptSection n1 = (ctx, eligible) -> null;
    RoutingPromptSection n2 = (ctx, eligible) -> null;
    var assembler = new RoutingPromptAssembler(List.of(n1, n2));
    assertThat(assembler.assemble(context(), candidates())).isNull();
  }

  @Test
  void priorityOrdering_lowerValueRendersFirst() {
    @Priority(100)
    class HighPriority implements RoutingPromptSection {
      @Override
      public String render(AgentRoutingContext c, List<AgentCandidate> e) {
        return "high-priority";
      }
    }

    @Priority(200)
    class LowPriority implements RoutingPromptSection {
      @Override
      public String render(AgentRoutingContext c, List<AgentCandidate> e) {
        return "low-priority";
      }
    }

    class NoPriority implements RoutingPromptSection {
      @Override
      public String render(AgentRoutingContext c, List<AgentCandidate> e) {
        return "no-priority";
      }
    }

    var assembler =
        new RoutingPromptAssembler(
            List.of(new NoPriority(), new LowPriority(), new HighPriority()));
    assertThat(assembler.assemble(context(), candidates()))
        .isEqualTo("high-priority\n\nlow-priority\n\nno-priority");
  }

  @Test
  void budget_fitsTwoOfThree_thirdDropped() {
    RoutingPromptSection s1 = (ctx, eligible) -> "aaaa"; // 4 chars
    RoutingPromptSection s2 = (ctx, eligible) -> "bbbb"; // 4 chars
    RoutingPromptSection s3 = (ctx, eligible) -> "cccc"; // 4 chars
    var assembler = new RoutingPromptAssembler(List.of(s1, s2, s3));
    // "aaaa\n\nbbbb" = 10 chars; adding "\n\ncccc" = 16; budget 12 drops s3
    assertThat(assembler.assemble(context(), candidates(), 12)).isEqualTo("aaaa\n\nbbbb");
  }

  @Test
  void budget_fitsNone_returnsNull() {
    RoutingPromptSection s1 = (ctx, eligible) -> "too long for budget";
    var assembler = new RoutingPromptAssembler(List.of(s1));
    assertThat(assembler.assemble(context(), candidates(), 5)).isNull();
  }

  @Test
  void budget_middleSkipped_lastFits() {
    RoutingPromptSection s1 = (ctx, eligible) -> "aa"; // 2 chars
    RoutingPromptSection s2 = (ctx, eligible) -> "bbbbbbbb"; // 8 chars — too big
    RoutingPromptSection s3 = (ctx, eligible) -> "cc"; // 2 chars
    var assembler = new RoutingPromptAssembler(List.of(s1, s2, s3));
    // "aa" = 2; adding "\n\nbbbbbbbb" = 12 > 8 budget; skip s2
    // "aa" = 2; adding "\n\ncc" = 6 <= 8; include s3
    assertThat(assembler.assemble(context(), candidates(), 8)).isEqualTo("aa\n\ncc");
  }

  @Test
  void budget_maxValue_behavesLikeNoBudget() {
    RoutingPromptSection s1 = (ctx, eligible) -> "section-1";
    RoutingPromptSection s2 = (ctx, eligible) -> "section-2";
    var assembler = new RoutingPromptAssembler(List.of(s1, s2));
    assertThat(assembler.assemble(context(), candidates(), Integer.MAX_VALUE))
        .isEqualTo("section-1\n\nsection-2");
  }

  private static AgentRoutingContext context() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "analysis", NullNode.instance, "test-tenant", List.of(), null, null);
  }

  private static List<AgentCandidate> candidates() {
    return List.of(
        new AgentCandidate("agent-1", Set.of("analysis"), 0, AgentHealth.READY, null, null, null));
  }
}
