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
package io.casehub.engine.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ValidationErrorTest {

  @RegisterExtension
  static final QuarkusUnitTest multipleTriggers =
      new QuarkusUnitTest()
          .withApplicationRoot(root -> root.addClasses(MultipleTriggerCase.class))
          .assertException(t -> assertThat(t.getMessage()).contains("multiple triggers"));

  @Case(namespace = "test", name = "Bad", version = "1.0.0")
  public interface MultipleTriggerCase {

    @Worker(capability = "work")
    @Bind(contextChange = ".ready", cron = "0 0 * * *")
    default String doWork(String input) {
      return input;
    }
  }

  @Test
  void multiple_triggers_rejected() {}
}
