package io.smallrye.graphql.client.vertx.typesafe;

import static java.util.stream.Collectors.toList;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

import org.jboss.logging.Logger;

import io.smallrye.graphql.client.InvalidResponseException;
import io.smallrye.graphql.client.impl.typesafe.HeaderBuilder;
import io.smallrye.graphql.client.impl.typesafe.QueryBuilder;
import io.smallrye.graphql.client.impl.typesafe.ResultBuilder;
import io.smallrye.graphql.client.impl.typesafe.reflection.FieldInfo;
import io.smallrye.graphql.client.impl.typesafe.reflection.MethodInvocation;
import io.smallrye.graphql.client.impl.typesafe.reflection.TypeInfo;
import io.smallrye.graphql.client.vertx.websocket.BuiltinWebsocketSubprotocolHandlers;
import io.smallrye.graphql.client.vertx.websocket.WebSocketSubprotocolHandler;
import io.smallrye.graphql.client.websocket.WebsocketSubprotocol;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebsocketVersion;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

class VertxTypesafeGraphQLClientProxy {

    private static final Logger log = Logger.getLogger(VertxTypesafeGraphQLClientProxy.class);

    private static final JsonBuilderFactory jsonObjectFactory = Json.createBuilderFactory(null);

    private final Map<String, String> queryCache = new HashMap<>();

    private final Map<String, String> additionalHeaders;
    private final URI endpoint;
    private final String websocketUrl;
    private final HttpClient httpClient;
    private final WebClient webClient;
    private final List<WebsocketSubprotocol> subprotocols;
    private final Integer subscriptionInitializationTimeout;
    private final Class<?> api;
    private final boolean executeSingleOperationsOverWebsocket;

    private Uni<WebSocketSubprotocolHandler> webSocketHandler;

    VertxTypesafeGraphQLClientProxy(
            Class<?> api,
            Map<String, String> additionalHeaders,
            URI endpoint,
            String websocketUrl,
            boolean executeSingleOperationsOverWebsocket,
            HttpClient httpClient,
            WebClient webClient,
            List<WebsocketSubprotocol> subprotocols,
            Integer subscriptionInitializationTimeout) {
        this.api = api;
        this.additionalHeaders = additionalHeaders;
        this.endpoint = endpoint;
        this.executeSingleOperationsOverWebsocket = executeSingleOperationsOverWebsocket;
        this.websocketUrl = websocketUrl;
        this.httpClient = httpClient;
        this.webClient = webClient;
        this.subprotocols = subprotocols;
        this.subscriptionInitializationTimeout = subscriptionInitializationTimeout;
    }

    Object invoke(MethodInvocation method) {
        if (method.isDeclaredInObject()) {
            return method.invoke(this);
        }

        MultiMap headers = HeadersMultiMap.headers()
                .addAll(new HeaderBuilder(api, method, additionalHeaders).build());
        JsonObject request = request(method);

        if (method.getReturnType().isUni()) {
            if (executeSingleOperationsOverWebsocket) {
                return executeSingleOperationOverWebsocket(method, request);
            } else {
                return Uni.createFrom()
                        .completionStage(postAsync(request.toString(), headers))
                        .map(response -> new ResultBuilder(method, response.bodyAsString()).read());
            }
        } else if (method.getReturnType().isMulti()) {
            Multi<String> rawMulti = Multi.createFrom().emitter(rawEmitter -> {
                webSocketHandler().subscribe().with(handler -> handler.executeMulti(request, rawEmitter));
            });
            return rawMulti
                    .onItem().transform(data -> {
                        Object object = new ResultBuilder(method, data).read();
                        if (object != null) {
                            return object;
                        } else {
                            throw new InvalidResponseException(
                                    "Couldn't find neither data nor errors in the response: " + data);
                        }
                    });
        } else {
            if (executeSingleOperationsOverWebsocket) {
                return executeSingleOperationOverWebsocket(method, request).await().indefinitely();
            } else {
                String response = postSync(request.toString(), headers);
                log.debugf("response graphql: %s", response);
                return new ResultBuilder(method, response).read();
            }
        }
    }

    private Uni<Object> executeSingleOperationOverWebsocket(MethodInvocation method, JsonObject request) {
        Uni<String> rawUni = Uni.createFrom().emitter(rawEmitter -> {
            webSocketHandler().subscribe().with(handler -> handler.executeUni(request, rawEmitter));
        });
        return rawUni
                .onItem().transform(data -> {
                    Object object = new ResultBuilder(method, data).read();
                    if (object != null) {
                        return object;
                    } else {
                        throw new InvalidResponseException(
                                "Couldn't find neither data nor errors in the response: " + data);
                    }
                });
    }

    private Uni<WebSocketSubprotocolHandler> webSocketHandler() {
        if (webSocketHandler == null) {
            webSocketHandler = Uni.createFrom().emitter(handlerEmitter -> {
                List<String> subprotocolIds = subprotocols.stream().map(i -> i.getProtocolId()).collect(toList());
                MultiMap headers = HeadersMultiMap.headers()
                        .addAll(new HeaderBuilder(api, null, additionalHeaders).build());
                httpClient.webSocketAbs(websocketUrl, headers, WebsocketVersion.V13, subprotocolIds,
                        result -> {
                            if (result.succeeded()) {
                                WebSocket webSocket = result.result();
                                WebSocketSubprotocolHandler handler = BuiltinWebsocketSubprotocolHandlers
                                        .createHandlerFor(webSocket.subProtocol(), webSocket,
                                                subscriptionInitializationTimeout);
                                handlerEmitter.complete(handler);
                                log.debug("Using websocket subprotocol handler: " + handler);
                            } else {
                                handlerEmitter.fail(result.cause());
                            }
                        });
            });
            return webSocketHandler;
        } else {
            return webSocketHandler;
        }
    }

    private JsonObject request(MethodInvocation method) {
        JsonObjectBuilder request = jsonObjectFactory.createObjectBuilder();
        String query = queryCache.computeIfAbsent(method.getKey(), key -> new QueryBuilder(method).build());
        request.add("query", query);
        request.add("variables", variables(method));
        request.add("operationName", method.getName());
        log.debugf("request graphql: %s", query);
        JsonObject result = request.build();
        log.debugf("full graphql request: %s", result.toString());
        return result;
    }

    private JsonObjectBuilder variables(MethodInvocation method) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        method.valueParameters().forEach(parameter -> builder.add(parameter.getRawName(), value(parameter.getValue())));
        return builder;
    }

    // TODO: the logic for serializing objects into JSON should probably be shared with server-side module
    // through a common module. Also this is not vert.x specific, another reason to move it out of this module
    private JsonValue value(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        TypeInfo type = TypeInfo.of(value.getClass());
        if (type.isScalar()) {
            return scalarValue(value);
        }
        if (type.isCollection()) {
            return arrayValue(value);
        }
        if (type.isMap()) {
            return mapValue(value);
        }
        return objectValue(value, type.fields());
    }

    private JsonValue scalarValue(Object value) {
        if (value instanceof String) {
            return Json.createValue((String) value);
        }
        if (value instanceof Date) {
            return Json.createValue(((Date) value).toInstant().toString());
        }
        if (value instanceof Enum) {
            return Json.createValue(((Enum<?>) value).name());
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof Byte) {
            return Json.createValue((Byte) value);
        }
        if (value instanceof Short) {
            return Json.createValue((Short) value);
        }
        if (value instanceof Integer) {
            return Json.createValue((Integer) value);
        }
        if (value instanceof Long) {
            return Json.createValue((Long) value);
        }
        if (value instanceof Double) {
            return Json.createValue((Double) value);
        }
        if (value instanceof Float) {
            return Json.createValue((Float) value);
        }
        if (value instanceof BigInteger) {
            return Json.createValue((BigInteger) value);
        }
        if (value instanceof BigDecimal) {
            return Json.createValue((BigDecimal) value);
        }
        return Json.createValue(value.toString());
    }

    private JsonArray arrayValue(Object value) {
        JsonArrayBuilder array = Json.createArrayBuilder();
        values(value).forEach(item -> array.add(value(item)));
        return array.build();
    }

    private JsonArray mapValue(Object value) {
        Map<?, ?> map = (Map<?, ?>) value;
        JsonArrayBuilder array = Json.createArrayBuilder();
        map.forEach((k, v) -> {
            JsonObjectBuilder entryBuilder = Json.createObjectBuilder();
            entryBuilder.add("key", value(k));
            entryBuilder.add("value", value(v));
            array.add(entryBuilder.build());
        });
        return array.build();
    }

    private Collection<?> values(Object value) {
        return value.getClass().isArray() ? array(value) : (Collection<?>) value;
    }

    private List<Object> array(Object value) {
        if (value.getClass().getComponentType().isPrimitive()) {
            return primitiveArray(value);
        }
        return Arrays.asList((Object[]) value);
    }

    private List<Object> primitiveArray(Object value) {
        int length = Array.getLength(value);
        List<Object> out = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            out.add(Array.get(value, i));
        }
        return out;
    }

    private JsonObject objectValue(Object object, Stream<FieldInfo> fields) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        fields.forEach(field -> {
            if (field.isIncludeNull() || field.get(object) != null) {
                builder.add(field.getName(), value(field.get(object)));
            }
        });
        return builder.build();
    }

    private CompletionStage<HttpResponse<Buffer>> postAsync(String request, MultiMap headers) {
        return webClient.postAbs(endpoint.toString())
                .putHeaders(headers)
                .sendBuffer(Buffer.buffer(request))
                .toCompletionStage();
    }

    private String postSync(String request, MultiMap headers) {
        Future<HttpResponse<Buffer>> future = webClient.postAbs(endpoint.toString())
                .putHeaders(headers)
                .sendBuffer(Buffer.buffer(request));
        try {
            HttpResponse<Buffer> result = future.toCompletionStage().toCompletableFuture().get();
            if (result.statusCode() != 200) {
                throw new RuntimeException("expected successful status code but got " +
                        result.statusCode() + " " + result.statusMessage() + ":\n" +
                        result.bodyAsString());
            }
            return result.bodyAsString();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Request failed", e);
        }
    }

    void close() {
        try {
            httpClient.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            webClient.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
