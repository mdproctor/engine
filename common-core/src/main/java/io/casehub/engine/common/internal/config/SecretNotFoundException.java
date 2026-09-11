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
package io.casehub.engine.common.internal.config;

/**
 * Thrown when a requested secret does not exist.
 *
 * <p>Fail-fast behavior: JQ expressions fail immediately if secret is missing. Error message must
 * NOT expose secret values or sensitive metadata.
 */
public class SecretNotFoundException extends RuntimeException {

  private final String secretName;

  public SecretNotFoundException(String secretName) {
    super("Secret not found: " + secretName);
    this.secretName = secretName;
  }

  public String getSecretName() {
    return secretName;
  }
}
