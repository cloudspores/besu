/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.handlers;

import static org.hyperledger.besu.ethereum.api.handlers.AbstractJsonRpcExecutor.handleJsonRpcError;

import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration;
import org.hyperledger.besu.ethereum.api.jsonrpc.context.ContextKey;
import org.hyperledger.besu.ethereum.api.jsonrpc.execution.JsonRpcExecutor;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRpcExecutorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(JsonRpcExecutorHandler.class);

  // Default timeout for RPC calls in seconds
  private static final long DEFAULT_TIMEOUT_SECONDS = 30;

  private JsonRpcExecutorHandler() {}

  public static Handler<RoutingContext> handler(
      final ObjectMapper jsonObjectMapper,
      final JsonRpcExecutor jsonRpcExecutor,
      final Tracer tracer,
      final JsonRpcConfiguration jsonRpcConfiguration) {
    return handler(jsonRpcExecutor, tracer, jsonRpcConfiguration);
  }

  public static Handler<RoutingContext> handler(
          final JsonRpcExecutor jsonRpcExecutor,
          final Tracer tracer,
          final JsonRpcConfiguration jsonRpcConfiguration) {
    return ctx -> {
      try {
        createExecutor(jsonRpcExecutor, tracer, ctx, jsonRpcConfiguration)
                .ifPresentOrElse(
                        executor -> {
                          CompletableFuture<Void> future = new CompletableFuture<>();

                          // Run the executor in a separate thread
                          CompletableFuture.runAsync(() -> {
                            try {
                              executor.execute();
                              future.complete(null);
                            } catch (IOException e) {
                              future.completeExceptionally(e);
                            }
                          });

                          // Set a timeout for the future
                          long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_TIMEOUT_SECONDS);
                          future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                                  .whenComplete((result, throwable) -> {
                                    if (throwable != null) {
                                      if (throwable instanceof TimeoutException) {
                                        handleJsonRpcError(ctx, null, RpcErrorType.TIMEOUT_ERROR);
                                      } else {
                                        final String method = executor.getRpcMethodName(ctx);
                                        LOG.error("{} - Error streaming JSON-RPC response", method, throwable);
                                        handleJsonRpcError(ctx, null, RpcErrorType.INTERNAL_ERROR);
                                      }
                                    } else {
                                      LOG.debug("JSON-RPC execution completed successfully");
                                    }
                                  });
                        },
                        () -> handleJsonRpcError(ctx, null, RpcErrorType.PARSE_ERROR));
      } catch (final RuntimeException e) {
        final String method = ctx.get(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name());
        LOG.error("Unhandled exception in JSON-RPC executor for method {}", method, e);
        handleJsonRpcError(ctx, null, RpcErrorType.INTERNAL_ERROR);
      }
    };
  }

  private static Optional<AbstractJsonRpcExecutor> createExecutor(
      final JsonRpcExecutor jsonRpcExecutor,
      final Tracer tracer,
      final RoutingContext ctx,
      final JsonRpcConfiguration jsonRpcConfiguration) {
    if (isJsonObjectRequest(ctx)) {
      return Optional.of(
          new JsonRpcObjectExecutor(jsonRpcExecutor, tracer, ctx, jsonRpcConfiguration));
    }
    if (isJsonArrayRequest(ctx)) {
      return Optional.of(
          new JsonRpcArrayExecutor(jsonRpcExecutor, tracer, ctx, jsonRpcConfiguration));
    }
    return Optional.empty();
  }

  private static boolean isJsonObjectRequest(final RoutingContext ctx) {
    return ctx.data().containsKey(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name());
  }

  private static boolean isJsonArrayRequest(final RoutingContext ctx) {
    return ctx.data().containsKey(ContextKey.REQUEST_BODY_AS_JSON_ARRAY.name());
  }
}
