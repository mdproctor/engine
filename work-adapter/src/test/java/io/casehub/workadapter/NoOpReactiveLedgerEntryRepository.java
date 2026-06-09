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
import java.util.Optional;
import java.util.UUID;

@Alternative
@Priority(1)
@ApplicationScoped
class NoOpReactiveLedgerEntryRepository implements ReactiveLedgerEntryRepository {

  @Override
  public Uni<LedgerEntry> save(final LedgerEntry entry, final String tenancyId) {
    return Uni.createFrom().item(entry);
  }

  @Override
  public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findBySubjectIdAndTimeRange(
      final UUID subjectId, final Instant from, final Instant to, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<Optional<LedgerEntry>> findLatestBySubjectId(
      final UUID subjectId, final String tenancyId) {
    return Uni.createFrom().item(Optional.empty());
  }

  @Override
  public Uni<Optional<LedgerEntry>> findEntryById(final UUID id, final String tenancyId) {
    return Uni.createFrom().item(Optional.empty());
  }

  @Override
  public Uni<List<LedgerEntry>> findByActorId(
      final String actorId, final Instant from, final Instant to, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findByActorRole(
      final String actorRole, final Instant from, final Instant to, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<LedgerAttestation> saveAttestation(
      final LedgerAttestation attestation, final String tenancyId) {
    return Uni.createFrom().item(attestation);
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryId(
      final UUID entryId, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryIdAndCapabilityTag(
      final UUID entryId, final String capabilityTag, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByEntryIdGlobal(
      final UUID entryId, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }

  @Override
  public Uni<List<LedgerAttestation>> findAttestationsByAttestorIdAndCapabilityTag(
      final String attestorId, final String capabilityTag, final String tenancyId) {
    return Uni.createFrom().item(List.of());
  }
}
