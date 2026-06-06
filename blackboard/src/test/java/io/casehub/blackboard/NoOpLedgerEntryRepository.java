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
package io.casehub.blackboard;

import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * No-op LedgerEntryRepository for engine tests. casehub-ledger is on the engine classpath (for
 * LedgerTraceIdProvider) but its JPA-backed beans require a LedgerEntryRepository that doesn't
 * exist in the in-memory test profile. This stub satisfies the dependency.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class NoOpLedgerEntryRepository implements LedgerEntryRepository {

  @Override
  public LedgerEntry save(LedgerEntry entry) {
    return entry;
  }

  @Override
  public List<LedgerEntry> findBySubjectId(UUID subjectId) {
    return List.of();
  }

  @Override
  public Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId) {
    return Optional.empty();
  }

  @Override
  public Optional<LedgerEntry> findEntryById(UUID id) {
    return Optional.empty();
  }

  @Override
  public List<LedgerAttestation> findAttestationsByEntryId(UUID ledgerEntryId) {
    return List.of();
  }

  @Override
  public LedgerAttestation saveAttestation(LedgerAttestation attestation) {
    return attestation;
  }

  @Override
  public List<LedgerEntry> listAll() {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findAllEvents() {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findEventsByActorId(String actorId) {
    return List.of();
  }

  @Override
  public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(Set<UUID> entryIds) {
    return Map.of();
  }

  @Override
  public List<LedgerEntry> findBySubjectIdAndTimeRange(UUID subjectId, Instant from, Instant to) {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to) {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findByActorRole(String actorRole, Instant from, Instant to) {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findByTimeRange(Instant from, Instant to) {
    return List.of();
  }

  @Override
  public List<LedgerEntry> findCausedBy(UUID entryId) {
    return List.of();
  }

  @Override
  public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(
      UUID entryId, String capabilityTag) {
    return List.of();
  }

  @Override
  public List<LedgerAttestation> findAttestationsByEntryIdGlobal(UUID entryId) {
    return List.of();
  }

  @Override
  public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(
      String attestorId, String capabilityTag) {
    return List.of();
  }
}
