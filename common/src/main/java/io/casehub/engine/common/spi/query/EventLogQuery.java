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
package io.casehub.engine.common.spi.query;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable query for paginated event log lookups.
 *
 * <p>{@code caseId} is required — event logs are always scoped to a case. All other filters are
 * optional. Pagination uses zero-based page numbering with a default page size of 50, capped at
 * 1000.
 */
public final class EventLogQuery {

  private static final int DEFAULT_SIZE = 50;
  private static final int MAX_SIZE = 1000;

  private final UUID caseId;
  private final Collection<CaseHubEventType> eventTypes;
  private final Collection<EventStreamType> streamTypes;
  private final int page;
  private final int size;

  private EventLogQuery(Builder b) {
    this.caseId = Objects.requireNonNull(b.caseId, "caseId is required");
    this.eventTypes = b.eventTypes;
    this.streamTypes = b.streamTypes;
    this.page = Math.max(0, b.page);
    this.size = Math.min(MAX_SIZE, Math.max(1, b.size));
  }

  public static Builder builder(UUID caseId) {
    return new Builder(caseId);
  }

  public UUID caseId() {
    return caseId;
  }

  public Collection<CaseHubEventType> eventTypes() {
    return eventTypes;
  }

  public Collection<EventStreamType> streamTypes() {
    return streamTypes;
  }

  public int page() {
    return page;
  }

  public int size() {
    return size;
  }

  public static final class Builder {

    private final UUID caseId;
    private Collection<CaseHubEventType> eventTypes;
    private Collection<EventStreamType> streamTypes;
    private int page = 0;
    private int size = DEFAULT_SIZE;

    Builder(UUID caseId) {
      this.caseId = caseId;
    }

    public Builder eventTypes(Collection<CaseHubEventType> eventTypes) {
      this.eventTypes = eventTypes;
      return this;
    }

    public Builder streamTypes(Collection<EventStreamType> streamTypes) {
      this.streamTypes = streamTypes;
      return this;
    }

    public Builder page(int page) {
      this.page = page;
      return this;
    }

    public Builder size(int size) {
      this.size = size;
      return this;
    }

    public EventLogQuery build() {
      return new EventLogQuery(this);
    }
  }
}
