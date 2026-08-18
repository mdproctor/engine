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
package io.casehub.engine.graphql.dto;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import org.eclipse.microprofile.graphql.Type;

import java.time.Instant;
import java.util.UUID;

@Type("CaseInstance")
public record CaseInstanceType(
        UUID caseId,
        CaseStatus status,
        String namespace,
        String name,
        String version,
        Instant createdAt,
        String actorId) {

    public static CaseInstanceType from(CaseInstance instance) {
        CaseMetaModel meta = instance.getCaseMetaModel();
        return new CaseInstanceType(
                instance.getUuid(),
                instance.getState(),
                meta.getNamespace(),
                meta.getName(),
                meta.getVersion(),
                instance.getCreatedAt(),
                instance.getActorId());
    }
}
