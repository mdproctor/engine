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
package io.casehub.ledger.quarkus;

import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.ledger.routing.DefaultTrustRoutingPolicyProvider;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustGatedAttestationPolicy;
import io.casehub.ledger.routing.TrustRoutingPreferenceRegistrar;
import io.casehub.ledger.routing.TrustSignalProvider;
import io.casehub.ledger.routing.TrustWeightedImplementationRoutingStrategy;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.service.CaseLedgerEventCapture;
import io.casehub.ledger.service.WorkerDecisionEventCapture;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class LedgerBeans {

  @Produces
  @DefaultBean
  DefaultTrustRoutingPolicyProvider defaultTrustRoutingPolicyProvider() {
    return new DefaultTrustRoutingPolicyProvider();
  }

  @Produces
  @DefaultBean
  CaseLedgerEntryRepository caseLedgerEntryRepository(@LedgerPersistenceUnit EntityManager em) {
    return new CaseLedgerEntryRepository(em);
  }

  @Produces
  @ApplicationScoped
  TrustCandidateClassifier trustCandidateClassifier() {
    return new TrustCandidateClassifier();
  }

  @Produces
  @ApplicationScoped
  TrustSignalProvider trustSignalProvider(
      TrustCandidateClassifier classifier,
      TrustScoreSource source,
      TrustRoutingPolicyProvider policyProvider) {
    return new TrustSignalProvider(classifier, source, policyProvider);
  }

  @Produces
  @ApplicationScoped
  TrustRoutingPreferenceRegistrar trustRoutingPreferenceRegistrar(
      PreferenceSchemaRegistry registry) {
    return new TrustRoutingPreferenceRegistrar(registry);
  }

  @Produces
  @ApplicationScoped
  CaseLedgerEventCapture caseLedgerEventCapture(
      LedgerEntryRepository ledgerRepo, LedgerConfig ledgerConfig) {
    return new CaseLedgerEventCapture(ledgerRepo, ledgerConfig);
  }

  @Produces
  @ApplicationScoped
  WorkerDecisionEventCapture workerDecisionEventCapture(
      LedgerEntryRepository ledgerRepo,
      LedgerConfig ledgerConfig,
      TrustScoreSource trustScoreSource,
      TrustRoutingPolicyProvider trustRoutingPolicyProvider) {
    return new WorkerDecisionEventCapture(
        ledgerRepo, ledgerConfig, trustScoreSource, trustRoutingPolicyProvider);
  }

  @Produces
  @Alternative
  @Priority(1)
  TrustWeightedImplementationRoutingStrategy trustWeightedImplementationRoutingStrategy(
      TrustCandidateClassifier classifier,
      TrustScoreSource source,
      TrustRoutingPolicyProvider policyProvider) {
    return new TrustWeightedImplementationRoutingStrategy(classifier, source, policyProvider);
  }

  @Produces
  @Alternative
  @Priority(1)
  TrustGatedAttestationPolicy trustGatedAttestationPolicy(
      TrustScoreSource source, TrustRoutingPolicyProvider policyProvider) {
    return new TrustGatedAttestationPolicy(source, policyProvider);
  }
}
