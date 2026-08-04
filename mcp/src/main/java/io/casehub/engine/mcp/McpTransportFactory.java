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
package io.casehub.engine.mcp;

import io.casehub.engine.common.internal.auth.AuthConfig;
import io.modelcontextprotocol.spec.McpClientTransport;

final class McpTransportFactory {

  private McpTransportFactory() {}

  static McpClientTransport create(final McpTransport transport) {
    return switch (transport) {
      case McpTransport.Stdio stdio -> createStdio(stdio);
      case McpTransport.Http http -> createHttp(http);
    };
  }

  private static McpClientTransport createStdio(final McpTransport.Stdio stdio) {
    final io.modelcontextprotocol.client.transport.ServerParameters params =
        io.modelcontextprotocol.client.transport.ServerParameters.builder(
                stdio.command().getFirst())
            .args(stdio.command().subList(1, stdio.command().size()).toArray(String[]::new))
            .build();
    return new io.modelcontextprotocol.client.transport.StdioClientTransport(
        params,
        new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(
            tools.jackson.databind.json.JsonMapper.builder().build()));
  }

  private static McpClientTransport createHttp(final McpTransport.Http http) {
    var builder =
        io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(
            http.url());
    if (http.auth().type() != AuthConfig.AuthType.NONE && http.auth().tokenConfigKey() != null) {
      final String token =
          org.eclipse.microprofile.config.ConfigProvider.getConfig()
              .getValue(http.auth().tokenConfigKey(), String.class);
      final java.net.http.HttpRequest.Builder requestBuilder =
          java.net.http.HttpRequest.newBuilder();
      switch (http.auth().type()) {
        case BEARER -> requestBuilder.header("Authorization", "Bearer " + token);
        case API_KEY -> requestBuilder.header("X-API-Key", token);
        default -> {}
      }
      builder.requestBuilder(requestBuilder);
    }
    return builder.build();
  }
}
