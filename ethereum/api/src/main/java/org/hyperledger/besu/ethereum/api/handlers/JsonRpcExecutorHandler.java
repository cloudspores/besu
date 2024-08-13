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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRpcExecutorHandler {
  private static final Logger LOG = LoggerFactory.getLogger(JsonRpcExecutorHandler.class);

  // Default timeout for RPC calls in seconds
  private static final long DEFAULT_TIMEOUT_MILLISECONDS = 5000;

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
      final long timerId = ctx.vertx().setTimer(DEFAULT_TIMEOUT_MILLISECONDS, id -> {
        final String method = ctx.get(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name()).toString();
        LOG.error("Timeout occurred in JSON-RPC executor for method {}", method);
        if (!ctx.response().ended()) {
          handleJsonRpcError(ctx, null, RpcErrorType.TIMEOUT_ERROR);
          ctx.fail(408); // HTTP 408 Request Timeout
          ctx.response().close();
        }
      });

      try {
        createExecutor(jsonRpcExecutor, tracer, ctx, jsonRpcConfiguration)
                .ifPresentOrElse(
                        executor -> {
                          try {
                            executor.execute();
                            ctx.vertx().cancelTimer(timerId); // Cancel the timer if execution completes
                          } catch (IOException e) {
                            final String method = executor.getRpcMethodName(ctx);
                            LOG.error("{} - Error streaming JSON-RPC response", method, e);
                            if (!ctx.response().ended()) {
                              handleJsonRpcError(ctx, null, RpcErrorType.INTERNAL_ERROR);
                              ctx.fail(500); // HTTP 500 Internal Server Error
                              ctx.response().close();
                            }
                          }
                        },
                        () -> {
                          if (!ctx.response().ended()) {
                            handleJsonRpcError(ctx, null, RpcErrorType.PARSE_ERROR);
                            ctx.fail(400); // HTTP 400 Bad Request
                            ctx.response().close();
                          }
                        });
      } catch (final RuntimeException e) {
        final String method = ctx.get(ContextKey.REQUEST_BODY_AS_JSON_OBJECT.name()).toString();
        LOG.error("Unhandled exception in JSON-RPC executor for method {}", method, e);
        if (!ctx.response().ended()) {
          handleJsonRpcError(ctx, null, RpcErrorType.INTERNAL_ERROR);
          ctx.fail(500); // HTTP 500 Internal Server Error
          ctx.response().close();
        }
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
