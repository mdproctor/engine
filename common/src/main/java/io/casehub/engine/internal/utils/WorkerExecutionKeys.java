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
package io.casehub.engine.internal.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

public final class WorkerExecutionKeys {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private WorkerExecutionKeys() {}

  public static String inputDataHash(Map<String, Object> inputData) {
    try {
      String json = OBJECT_MAPPER.writeValueAsString(inputData);

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));

      StringBuilder hexString = new StringBuilder();
      for (byte b : hashBytes) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new RuntimeException("Failed to compute input data hash", e);
    }
  }

  /**
   * Generates a globally unique correlation key for orchestrated work, including caseId to prevent
   * cross-case collisions.
   *
   * @param caseId the case UUID
   * @param workerName the worker name
   * @param capabilityName the capability name
   * @param inputData the input data map
   * @return correlation key in format: caseId:workerName:capabilityName:hash(inputData)
   */
  public static String inputDataHash(
      UUID caseId, String workerName, String capabilityName, Map<String, Object> inputData) {
    return inputDataHash(caseId, workerName, capabilityName, inputDataHash(inputData));
  }

  /**
   * Generates a globally unique correlation key for orchestrated work, including caseId to prevent
   * cross-case collisions.
   *
   * @param caseId the case UUID
   * @param workerName the worker name
   * @param capabilityName the capability name
   * @param inputDataHash pre-computed hash of input data
   * @return correlation key in format: caseId:workerName:capabilityName:inputDataHash
   */
  public static String inputDataHash(
      UUID caseId, String workerName, String capabilityName, String inputDataHash) {
    return caseId + ":" + workerName + ":" + capabilityName + ":" + inputDataHash;
  }
}
