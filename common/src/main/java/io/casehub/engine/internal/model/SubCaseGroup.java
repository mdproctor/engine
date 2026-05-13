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
package io.casehub.engine.internal.model;

import io.casehub.api.model.OnThresholdReached;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SubCaseGroup {
  private UUID parentCaseId;
  private String groupId;
  private int instanceCount;
  private int requiredCount;
  private int completedCount;
  private int rejectedCount;
  private boolean policyTriggered;
  private OnThresholdReached onThresholdReached;
  private final Set<UUID> childCaseIds = new HashSet<>();

  public UUID getParentCaseId() {
    return parentCaseId;
  }

  public void setParentCaseId(UUID v) {
    parentCaseId = v;
  }

  public String getGroupId() {
    return groupId;
  }

  public void setGroupId(String v) {
    groupId = v;
  }

  public int getInstanceCount() {
    return instanceCount;
  }

  public void setInstanceCount(int v) {
    instanceCount = v;
  }

  public int getRequiredCount() {
    return requiredCount;
  }

  public void setRequiredCount(int v) {
    requiredCount = v;
  }

  public int getCompletedCount() {
    return completedCount;
  }

  public void setCompletedCount(int v) {
    completedCount = v;
  }

  public int getRejectedCount() {
    return rejectedCount;
  }

  public void setRejectedCount(int v) {
    rejectedCount = v;
  }

  public boolean isPolicyTriggered() {
    return policyTriggered;
  }

  public void setPolicyTriggered(boolean v) {
    policyTriggered = v;
  }

  public OnThresholdReached getOnThresholdReached() {
    return onThresholdReached;
  }

  public void setOnThresholdReached(OnThresholdReached v) {
    onThresholdReached = v;
  }

  public Set<UUID> getChildCaseIds() {
    return Set.copyOf(childCaseIds);
  }

  public void addChildCaseId(UUID id) {
    childCaseIds.add(id);
  }

  public void addAllChildCaseIds(Set<UUID> ids) {
    childCaseIds.addAll(ids);
  }
}
