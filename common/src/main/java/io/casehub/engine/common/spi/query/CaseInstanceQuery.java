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

import io.casehub.api.model.CaseStatus;

/**
 * Immutable query for paginated case instance lookups.
 *
 * <p>All filters are optional — omitting a field means "no constraint on that dimension".
 * Pagination uses zero-based page numbering with a default page size of 20, capped at 100.
 */
public final class CaseInstanceQuery {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;

  private final String namespace;
  private final String name;
  private final CaseStatus status;
  private final int page;
  private final int size;

  private CaseInstanceQuery(Builder b) {
    this.namespace = b.namespace;
    this.name = b.name;
    this.status = b.status;
    this.page = Math.max(0, b.page);
    this.size = Math.min(MAX_SIZE, Math.max(1, b.size));
  }

  public static CaseInstanceQuery all() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public String namespace() {
    return namespace;
  }

  public String name() {
    return name;
  }

  public CaseStatus status() {
    return status;
  }

  public int page() {
    return page;
  }

  public int size() {
    return size;
  }

  public static final class Builder {

    private String namespace;
    private String name;
    private CaseStatus status;
    private int page = 0;
    private int size = DEFAULT_SIZE;

    private Builder() {}

    public Builder namespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder status(CaseStatus status) {
      this.status = status;
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

    public CaseInstanceQuery build() {
      return new CaseInstanceQuery(this);
    }
  }
}
