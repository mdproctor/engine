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

import java.util.Objects;

/**
 * Identifies a child case definition to launch as part of a Stage's work. SubCase binding wiring:
 * casehubio/engine#195. M-of-N coordination: casehubio/engine#112.
 */
public class SubCase {
  private final String namespace;
  private final String name;
  private final String version;
  private final SubCaseCompletionStrategy completionStrategy;
  private final boolean waitForCompletion;
  private final String inputMapping;
  private final String outputMapping;
  private final String groupId;
  private final int totalInGroup;
  private final int requiredCount;
  private final OnThresholdReached onThresholdReached;

  private SubCase(Builder b) {
    this.namespace = Objects.requireNonNull(b.namespace, "namespace");
    this.name = Objects.requireNonNull(b.name, "name");
    this.version = Objects.requireNonNull(b.version, "version");
    this.completionStrategy =
        b.completionStrategy != null
            ? b.completionStrategy
            : new DefaultSubCaseCompletionStrategy();
    this.waitForCompletion = b.waitForCompletion;
    this.inputMapping = b.inputMapping != null ? b.inputMapping : ".";
    this.outputMapping = b.outputMapping;
    this.groupId = b.groupId;
    this.totalInGroup = b.totalInGroup;
    this.requiredCount = b.requiredCount > 0 ? b.requiredCount : b.totalInGroup;
    this.onThresholdReached =
        b.onThresholdReached != null ? b.onThresholdReached : OnThresholdReached.KEEP;
  }

  public String namespace() {
    return namespace;
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  public SubCaseCompletionStrategy completionStrategy() {
    return completionStrategy;
  }

  public boolean waitForCompletion() {
    return waitForCompletion;
  }

  public String inputMapping() {
    return inputMapping;
  }

  public String outputMapping() {
    return outputMapping;
  }

  public String groupId() {
    return groupId;
  }

  public int totalInGroup() {
    return totalInGroup;
  }

  public int requiredCount() {
    return requiredCount;
  }

  public OnThresholdReached onThresholdReached() {
    return onThresholdReached;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String namespace;
    private String name;
    private String version;
    private SubCaseCompletionStrategy completionStrategy;
    private boolean waitForCompletion = true;
    private String inputMapping;
    private String outputMapping;
    private String groupId;
    private int totalInGroup = 0;
    private int requiredCount = 0;
    private OnThresholdReached onThresholdReached;

    public Builder namespace(String v) {
      namespace = v;
      return this;
    }

    public Builder name(String v) {
      name = v;
      return this;
    }

    public Builder version(String v) {
      version = v;
      return this;
    }

    public Builder completionStrategy(SubCaseCompletionStrategy s) {
      completionStrategy = s;
      return this;
    }

    public Builder waitForCompletion(boolean v) {
      waitForCompletion = v;
      return this;
    }

    public Builder inputMapping(String v) {
      inputMapping = v;
      return this;
    }

    public Builder outputMapping(String v) {
      outputMapping = v;
      return this;
    }

    public Builder groupId(String v) {
      groupId = v;
      return this;
    }

    public Builder totalInGroup(int v) {
      totalInGroup = v;
      return this;
    }

    public Builder requiredCount(int v) {
      requiredCount = v;
      return this;
    }

    public Builder onThresholdReached(OnThresholdReached v) {
      onThresholdReached = v;
      return this;
    }

    public SubCase build() {
      return new SubCase(this);
    }
  }
}
