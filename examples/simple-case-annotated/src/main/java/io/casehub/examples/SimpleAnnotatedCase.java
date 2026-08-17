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
package io.casehub.examples;

import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Goal;
import io.casehub.engine.annotations.Milestone;
import io.casehub.engine.annotations.Worker;

@Case(namespace = "example", name = "Simple Document Processing", version = "1.0.0")
public interface SimpleAnnotatedCase {

  @Worker(capability = "processDocument")
  @Bind(contextChange = ".status == 'processing'")
  default ProcessedDocument process(String documentId, String status) {
    return new ProcessedDocument(documentId, "Processed content for " + documentId, "processed");
  }

  @Milestone(
      name = "documentProcessed",
      completionCriteria = ".processedDocument.status == 'processed'")
  default void documentProcessed() {}

  @Goal(value = "Document processing complete", condition = ".processedDocument != null")
  default void done() {}

  record ProcessedDocument(String id, String content, String status) {}
}
