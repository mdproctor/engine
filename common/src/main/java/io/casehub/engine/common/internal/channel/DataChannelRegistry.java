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
package io.casehub.engine.common.internal.channel;

import io.casehub.api.spi.DataChannelFactory;
import io.casehub.worker.api.DataChannel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DataChannelRegistry {

  private record ChannelKey(UUID caseId, String name) {}

  private record ChannelEntry(DataChannel<?> channel, Class<?> recordType) {}

  private final ConcurrentHashMap<ChannelKey, ChannelEntry> channels = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Set<ChannelKey>> executionIndex =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Set<ChannelKey>> scopeIndex = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public <T> DataChannel<T> getOrCreate(
      UUID caseId, String name, Class<T> recordType, DataChannelFactory factory) {
    final ChannelKey key = new ChannelKey(caseId, name);
    final ChannelEntry entry =
        channels.compute(
            key,
            (k, existing) -> {
              if (existing != null) {
                if (!existing.recordType().equals(recordType)) {
                  throw new IllegalArgumentException(
                      "Channel '"
                          + name
                          + "' already exists with type "
                          + existing.recordType().getSimpleName()
                          + " but requested type "
                          + recordType.getSimpleName());
                }
                return existing;
              }
              return new ChannelEntry(factory.create(name, recordType, caseId), recordType);
            });
    return (DataChannel<T>) entry.channel();
  }

  @SuppressWarnings("unchecked")
  public <T> DataChannel<T> get(UUID caseId, String name) {
    final ChannelKey key = new ChannelKey(caseId, name);
    final ChannelEntry entry = channels.get(key);
    if (entry == null) {
      throw new IllegalArgumentException("No channel '" + name + "' for case " + caseId);
    }
    return (DataChannel<T>) entry.channel();
  }

  public void trackExecution(UUID caseId, String channelName, String executionId) {
    executionIndex
        .computeIfAbsent(executionId, k -> ConcurrentHashMap.newKeySet())
        .add(new ChannelKey(caseId, channelName));
  }

  public void trackScope(UUID caseId, String channelName, String scopeId) {
    scopeIndex
        .computeIfAbsent(scopeId, k -> ConcurrentHashMap.newKeySet())
        .add(new ChannelKey(caseId, channelName));
  }

  public void closeByCase(UUID caseId) {
    channels
        .entrySet()
        .removeIf(
            entry -> {
              if (entry.getKey().caseId().equals(caseId)) {
                entry.getValue().channel().close();
                return true;
              }
              return false;
            });
  }

  public void closeByExecution(UUID caseId, String executionId) {
    final Set<ChannelKey> tracked = executionIndex.remove(executionId);
    if (tracked == null) return;
    for (final ChannelKey key : tracked) {
      final ChannelEntry removed = channels.remove(key);
      if (removed != null) {
        removed.channel().close();
      }
    }
  }

  public void closeByScope(UUID caseId, String scopeId) {
    final Set<ChannelKey> tracked = scopeIndex.remove(scopeId);
    if (tracked == null) return;
    for (final ChannelKey key : tracked) {
      final ChannelEntry removed = channels.remove(key);
      if (removed != null) {
        removed.channel().close();
      }
    }
  }
}
