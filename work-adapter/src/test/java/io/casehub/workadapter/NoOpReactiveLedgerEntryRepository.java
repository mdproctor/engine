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
package io.casehub.workadapter;

import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.smallrye.mutiny.Uni;
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
 * No-op {@link ReactiveLedgerEntryRepository} for work-adapter tests. casehub-ledger's reactive JPA
 * implementation requires a datasource; this stub satisfies the CDI dependency without one.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class NoOpReactiveLedgerEntryRepository implements ReactiveLedgerEntryRepository {

  @Override
  public Uni<LedgerEntry> save(LedgerEntry entry) {
    return Uni.createFrom().item(entry);
  }

  @Override
  public Uni<List<LedgerEntry>> listAll() {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findBySubjectId(UUID subjectId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findBySubjectIdAndTimeRange(
      UUID subjectId, Instant from, Instant to) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<Optional<LedgerEntry>> findLatestBySubjectId(UUID subjectId) {
    return Uni.createFrom().item(Optional.empty());
  }

  @Override
  public Uni<Optional<LedgerEntry>> findEntryById(UUID id) {
    return Uni.createFrom().item(Optional.empty());
  }

  @Override
  public Uni<List<LedgerEntry>> findAllEvents() {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findByActorId(String actorId, Instant from, Instant to) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findByActorRole(String actorRole, Instant from, Instant to) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findByTimeRange(Instant from, Instant to) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findCausedBy(UUID entryId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<LedgerAttestation> saveAttestation(LedgerAttestation attestation) {
    return Uni.createFrom().item(attestation);
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryId(UUID ledgerEntryId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(Set<UUID> entryIds) {
    return Uni.createFrom().item(Map.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(
      UUID entryId, String capabilityTag) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryIdGlobal(UUID entryId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(
      String attestorId, String capabilityTag) {
    return Uni.createFrom().item(List.of());
  }
}
