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
package io.casehub.engine.rest.health;

import io.casehub.platform.api.acl.AccessControlProvider;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AclEnforcementHealthCheck {

  private static final Logger LOG = Logger.getLogger(AclEnforcementHealthCheck.class);

  @Inject AccessControlProvider accessControlProvider;

  void onStart(@Observes StartupEvent ev) {
    boolean isDefaultImpl = accessControlProvider.canAccess("__probe__", "__probe__", null);
    if (!isDefaultImpl) {
      LOG.warn(
          "ACL enforcement is active (provider: "
              + accessControlProvider.getClass().getSimpleName()
              + ") — ensure grants are provisioned via AccessControlProvider.grant() "
              + "or the authorization: YAML block. Without grants, all case access will be denied.");
    } else {
      LOG.infof(
          "ACL enforcement: permissive (provider: %s)",
          accessControlProvider.getClass().getSimpleName());
    }
  }
}
