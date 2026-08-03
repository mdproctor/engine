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
package io.casehub.api.model;

import java.util.Map;

/**
 * Structured error information for worker outcomes and system failures.
 *
 * <p>Carries machine-readable error codes, human-readable messages, optional context data, and
 * recoverability hints.
 *
 * @param errorCode machine-readable error identifier (e.g., "TIMEOUT", "VALIDATION_FAILED")
 * @param message human-readable error description
 * @param context optional diagnostic context (e.g., failed field names, threshold values)
 * @param recoverable whether the error is potentially recoverable via retry or alternate path
 */
public record ErrorInfo(
    String errorCode, String message, Map<String, Object> context, boolean recoverable) {

  /**
   * Creates an ErrorInfo with no context.
   *
   * @param errorCode machine-readable error identifier
   * @param message human-readable error description
   * @param recoverable whether the error is potentially recoverable
   * @return new ErrorInfo instance
   */
  public static ErrorInfo of(String errorCode, String message, boolean recoverable) {
    return new ErrorInfo(errorCode, message, Map.of(), recoverable);
  }

  /**
   * Creates an ErrorInfo with optional context.
   *
   * @param errorCode machine-readable error identifier
   * @param message human-readable error description
   * @param context diagnostic context (nullable, will be copied if non-null)
   * @param recoverable whether the error is potentially recoverable
   * @return new ErrorInfo instance
   */
  public static ErrorInfo of(
      String errorCode, String message, Map<String, Object> context, boolean recoverable) {
    return new ErrorInfo(
        errorCode, message, context != null ? Map.copyOf(context) : Map.of(), recoverable);
  }
}
