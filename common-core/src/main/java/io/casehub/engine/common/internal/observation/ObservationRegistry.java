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
package io.casehub.engine.common.internal.observation;

import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.InterestDeclaration;
import io.casehub.api.spi.observation.InterestLandscape;
import io.casehub.api.spi.observation.Observation;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ObservationRegistry implements Resettable {

  record ObserverRegistration(
      EnvironmentObserver observer,
      String agentId,
      String bindingName,
      String instanceId,
      InterestDeclaration declaration) {

    ObserverRegistration(
        EnvironmentObserver observer, String agentId, String bindingName, String instanceId) {
      this(observer, agentId, bindingName, instanceId, null);
    }
  }

  private final ConcurrentHashMap<UUID, List<ObserverRegistration>> registrations =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, List<Observation>>> observations =
      new ConcurrentHashMap<>();
  private final AtomicInteger instanceCounter = new AtomicInteger();

  public String registerObserver(
      UUID caseId,
      String agentId,
      String bindingName,
      EnvironmentObserver observer,
      int maxPerCase) {
    if (observer == null) {
      throw new IllegalArgumentException("observer must not be null");
    }
    if (observer.observerType() == null) {
      throw new IllegalArgumentException("observerType() must not be null");
    }
    if (observer.watchedKeys() == null) {
      throw new IllegalArgumentException("watchedKeys() must not be null");
    }

    var caseRegistrations =
        registrations.computeIfAbsent(caseId, k -> Collections.synchronizedList(new ArrayList<>()));
    synchronized (caseRegistrations) {
      if (caseRegistrations.size() >= maxPerCase) {
        return null;
      }
      String instanceId = observer.observerType() + "-" + instanceCounter.incrementAndGet();
      caseRegistrations.add(new ObserverRegistration(observer, agentId, bindingName, instanceId));
      return instanceId;
    }
  }

  public String registerObserver(
      UUID caseId,
      String agentId,
      String bindingName,
      EnvironmentObserver observer,
      int maxPerCase,
      InterestDeclaration declaration) {
    if (observer == null) {
      throw new IllegalArgumentException("observer must not be null");
    }
    if (observer.observerType() == null) {
      throw new IllegalArgumentException("observerType() must not be null");
    }
    if (observer.watchedKeys() == null) {
      throw new IllegalArgumentException("watchedKeys() must not be null");
    }

    var caseRegistrations =
        registrations.computeIfAbsent(caseId, k -> Collections.synchronizedList(new ArrayList<>()));
    synchronized (caseRegistrations) {
      if (caseRegistrations.size() >= maxPerCase) {
        return null;
      }
      String instanceId = observer.observerType() + "-" + instanceCounter.incrementAndGet();
      caseRegistrations.add(
          new ObserverRegistration(observer, agentId, bindingName, instanceId, declaration));
      return instanceId;
    }
  }

  public void unregisterByAgent(UUID caseId, String agentId) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations != null) {
      synchronized (caseRegistrations) {
        caseRegistrations.removeIf(r -> r.agentId().equals(agentId));
      }
    }
  }

  public void unregisterByBinding(UUID caseId, Set<String> bindingNames) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations != null) {
      synchronized (caseRegistrations) {
        caseRegistrations.removeIf(r -> bindingNames.contains(r.bindingName()));
      }
    }
  }

  public void unregisterByCase(UUID caseId) {
    registrations.remove(caseId);
    observations.remove(caseId);
  }

  public Map<String, List<EnvironmentObserver>> getObservers(UUID caseId) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations == null) {
      return Map.of();
    }
    Map<String, List<EnvironmentObserver>> result = new LinkedHashMap<>();
    synchronized (caseRegistrations) {
      for (var reg : caseRegistrations) {
        result.computeIfAbsent(reg.agentId(), k -> new ArrayList<>()).add(reg.observer());
      }
    }
    return result;
  }

  public int observerCount(UUID caseId) {
    var caseRegistrations = registrations.get(caseId);
    return caseRegistrations == null ? 0 : caseRegistrations.size();
  }

  public void storeObservations(UUID caseId, String agentId, List<Observation> obs) {
    observations
        .computeIfAbsent(caseId, k -> new ConcurrentHashMap<>())
        .put(agentId, List.copyOf(obs));
  }

  public List<Observation> getObservations(UUID caseId, String agentId) {
    var caseObs = observations.get(caseId);
    if (caseObs == null) {
      return List.of();
    }
    return caseObs.getOrDefault(agentId, List.of());
  }

  public Map<String, List<Observation>> getAllObservations(UUID caseId) {
    var caseObs = observations.get(caseId);
    return caseObs == null ? Map.of() : Map.copyOf(caseObs);
  }

  public List<ObserverRegistration> getRegistrationsForAgent(UUID caseId, String agentId) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations == null) {
      return List.of();
    }
    synchronized (caseRegistrations) {
      return caseRegistrations.stream().filter(r -> r.agentId().equals(agentId)).toList();
    }
  }

  public void deregisterByInstanceId(UUID caseId, String instanceId) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations != null) {
      synchronized (caseRegistrations) {
        caseRegistrations.removeIf(r -> r.instanceId().equals(instanceId));
      }
    }
  }

  public InterestLandscape computeLandscape(UUID caseId) {
    var caseRegistrations = registrations.get(caseId);
    if (caseRegistrations == null) {
      return InterestLandscape.EMPTY;
    }
    Map<String, Integer> keyCounts = new LinkedHashMap<>();
    Map<String, Integer> signalCounts = new LinkedHashMap<>();
    Map<String, Integer> typeCounts = new LinkedHashMap<>();
    int total;
    synchronized (caseRegistrations) {
      total = caseRegistrations.size();
      for (var reg : caseRegistrations) {
        String type = reg.observer().observerType();
        typeCounts.merge(type, 1, Integer::sum);
        for (String key : reg.observer().watchedKeys()) {
          keyCounts.merge(key, 1, Integer::sum);
        }
        if (reg.declaration() instanceof InterestDeclaration.SignalThreshold st) {
          signalCounts.merge(st.signalName(), 1, Integer::sum);
        } else if ("signal-strength".equals(type) && reg.declaration() == null) {
          signalCounts.merge("unknown-signal", 1, Integer::sum);
        }
      }
    }
    return new InterestLandscape(
        Map.copyOf(keyCounts), Map.copyOf(signalCounts), Map.copyOf(typeCounts), total);
  }

  @Override
  public void reset() {
    registrations.clear();
    observations.clear();
    instanceCounter.set(0);
  }
}
