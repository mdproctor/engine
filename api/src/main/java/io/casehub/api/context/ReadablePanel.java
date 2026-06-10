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
package io.casehub.api.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ReadablePanel {

  String panelName();

  boolean isReadOnly();

  Object get(String key);

  <T> T getAs(String key, Class<T> type);

  <T> T getOrDefault(String key, T defaultValue);

  String getString(String key);

  Integer getInt(String key);

  Long getLong(String key);

  Double getDouble(String key);

  Boolean getBoolean(String key);

  <T> List<T> getList(String key, Class<T> elementType);

  boolean contains(String key);

  Set<String> getKeys();

  Map<String, Object> getData();

  Map<String, Object> getAll(String... keys);

  Object getPath(String path);

  String getPathAsString(String path);

  boolean isEmpty();

  int size();

  long getVersion();

  JsonNode asJsonNode();

  ReadablePanel snapshot();
}
