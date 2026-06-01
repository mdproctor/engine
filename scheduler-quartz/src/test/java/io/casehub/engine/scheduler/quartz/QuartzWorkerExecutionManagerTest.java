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
package io.casehub.engine.scheduler.quartz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;

class QuartzWorkerExecutionManagerTest {

  @Test
  void getActiveCaseIds_returnsUuidForMatchingWorker() throws Exception {
    final UUID caseUuid = UUID.randomUUID();
    final Scheduler scheduler = mock(Scheduler.class);
    final JobKey jobKey = JobKey.jobKey("key-1", "group-1");
    final JobDetail detail = mock(JobDetail.class);
    final JobDataMap dataMap = new JobDataMap();
    dataMap.put("workerId", "agent-x");
    dataMap.put("caseHubInstanceUuid", caseUuid.toString());

    when(scheduler.getJobGroupNames()).thenReturn(List.of("group-1"));
    when(scheduler.getJobKeys(GroupMatcher.groupEquals("group-1"))).thenReturn(Set.of(jobKey));
    when(scheduler.getJobDetail(jobKey)).thenReturn(detail);
    when(detail.getJobDataMap()).thenReturn(dataMap);

    final QuartzWorkerExecutionManager manager = new QuartzWorkerExecutionManager(scheduler);
    final List<UUID> result = manager.getActiveCaseIds("agent-x");

    assertEquals(1, result.size());
    assertEquals(caseUuid, result.get(0));
  }

  @Test
  void getActiveCaseIds_wrongWorker_returnsEmpty() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    final JobKey jobKey = JobKey.jobKey("key-2", "group-1");
    final JobDetail detail = mock(JobDetail.class);
    final JobDataMap dataMap = new JobDataMap();
    dataMap.put("workerId", "agent-other");
    dataMap.put("caseHubInstanceUuid", UUID.randomUUID().toString());

    when(scheduler.getJobGroupNames()).thenReturn(List.of("group-1"));
    when(scheduler.getJobKeys(GroupMatcher.groupEquals("group-1"))).thenReturn(Set.of(jobKey));
    when(scheduler.getJobDetail(jobKey)).thenReturn(detail);
    when(detail.getJobDataMap()).thenReturn(dataMap);

    final QuartzWorkerExecutionManager manager = new QuartzWorkerExecutionManager(scheduler);
    assertTrue(manager.getActiveCaseIds("agent-x").isEmpty());
  }

  @Test
  void getActiveCaseIds_multipleJobsSameWorker_returnsAllCaseIds() throws Exception {
    final UUID case1 = UUID.randomUUID();
    final UUID case2 = UUID.randomUUID();
    final Scheduler scheduler = mock(Scheduler.class);

    final JobKey key1 = JobKey.jobKey("key-3a", "group-1");
    final JobKey key2 = JobKey.jobKey("key-3b", "group-1");
    final JobDetail detail1 = mock(JobDetail.class);
    final JobDetail detail2 = mock(JobDetail.class);
    final JobDataMap map1 = new JobDataMap();
    map1.put("workerId", "agent-x");
    map1.put("caseHubInstanceUuid", case1.toString());
    final JobDataMap map2 = new JobDataMap();
    map2.put("workerId", "agent-x");
    map2.put("caseHubInstanceUuid", case2.toString());

    when(scheduler.getJobGroupNames()).thenReturn(List.of("group-1"));
    when(scheduler.getJobKeys(GroupMatcher.groupEquals("group-1"))).thenReturn(Set.of(key1, key2));
    when(scheduler.getJobDetail(key1)).thenReturn(detail1);
    when(scheduler.getJobDetail(key2)).thenReturn(detail2);
    when(detail1.getJobDataMap()).thenReturn(map1);
    when(detail2.getJobDataMap()).thenReturn(map2);

    final QuartzWorkerExecutionManager manager = new QuartzWorkerExecutionManager(scheduler);
    final List<UUID> result = manager.getActiveCaseIds("agent-x");

    assertEquals(2, result.size());
    assertTrue(result.contains(case1));
    assertTrue(result.contains(case2));
  }

  @Test
  void getActiveCaseIds_schedulerThrows_returnsEmpty() throws Exception {
    final Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getJobGroupNames()).thenThrow(new SchedulerException("test"));

    final QuartzWorkerExecutionManager manager = new QuartzWorkerExecutionManager(scheduler);
    assertTrue(manager.getActiveCaseIds("agent-x").isEmpty());
  }
}
