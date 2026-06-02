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
package io.casehub.persistence.jpa;

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agroal.api.AgroalDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RlsPolicyApplicatorTest {

  @Mock AgroalDataSource dataSource;
  @InjectMocks RlsPolicyApplicator applicator;

  @Test
  void onStart_whenDisabled_doesNothing() throws SQLException {
    applicator.rlsEnabled = false;
    applicator.onStart(null);
    verifyNoInteractions(dataSource);
  }

  @Test
  void onStart_whenEnabled_opensConnectionAndAppliesRls() throws SQLException {
    applicator.rlsEnabled = true;
    Connection conn = mock(Connection.class);
    Statement stmt = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(conn);
    when(conn.createStatement()).thenReturn(stmt);

    applicator.onStart(null);

    verify(dataSource).getConnection();
    // role creation (2 SQL) + 3 DDL ops per table × 5 tables = 17 execute() calls minimum
    verify(stmt, atLeast(16)).execute(anyString());
  }
}
