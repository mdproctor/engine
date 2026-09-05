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
package io.casehub.engine.common.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Engine-owned request type for inbound work item creation. Mirrors the essential fields from
 * casehub-work's {@code WorkItemCreateRequest} without introducing a compile dependency.
 *
 * <p>The work-side implementation of {@link InboundWorkItemScheduler} converts this to a {@code
 * WorkItemCreateRequest}.
 *
 * <p>Refs engine#974.
 */
public record InboundWorkItemRequest(
    String title,
    @Nullable String description,
    @Nullable String candidateGroups,
    @Nullable String candidateUsers,
    @Nullable String callerRef,
    @Nullable String scope,
    @Nullable String payload,
    String tenancyId,
    @Nullable String createdBy,
    @Nullable String priority,
    @Nullable List<String> types,
    @Nullable Instant expiresAt) {

  public InboundWorkItemRequest {
    if (title == null || title.isBlank())
      throw new IllegalArgumentException("title must not be blank");
    if (tenancyId == null || tenancyId.isBlank())
      throw new IllegalArgumentException("tenancyId must not be blank");
    types = types != null ? List.copyOf(types) : null;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String title;
    private String description;
    private String candidateGroups;
    private String candidateUsers;
    private String callerRef;
    private String scope;
    private String payload;
    private String tenancyId;
    private String createdBy;
    private String priority;
    private List<String> types;
    private Instant expiresAt;

    private Builder() {}

    public Builder title(String v) {
      this.title = v;
      return this;
    }

    public Builder description(String v) {
      this.description = v;
      return this;
    }

    public Builder candidateGroups(String v) {
      this.candidateGroups = v;
      return this;
    }

    public Builder candidateUsers(String v) {
      this.candidateUsers = v;
      return this;
    }

    public Builder callerRef(String v) {
      this.callerRef = v;
      return this;
    }

    public Builder scope(String v) {
      this.scope = v;
      return this;
    }

    public Builder payload(String v) {
      this.payload = v;
      return this;
    }

    public Builder tenancyId(String v) {
      this.tenancyId = v;
      return this;
    }

    public Builder createdBy(String v) {
      this.createdBy = v;
      return this;
    }

    public Builder priority(String v) {
      this.priority = v;
      return this;
    }

    public Builder types(List<String> v) {
      this.types = v;
      return this;
    }

    public Builder expiresAt(Instant v) {
      this.expiresAt = v;
      return this;
    }

    public InboundWorkItemRequest build() {
      return new InboundWorkItemRequest(
          title,
          description,
          candidateGroups,
          candidateUsers,
          callerRef,
          scope,
          payload,
          tenancyId,
          createdBy,
          priority,
          types,
          expiresAt);
    }
  }
}
