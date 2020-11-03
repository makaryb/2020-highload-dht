package ru.mail.polis.service.boriskin;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static ru.mail.polis.service.boriskin.NewService.resp;
import static ru.mail.polis.service.boriskin.ReplicaWorker.PROXY_HEADER_NAME;
import static ru.mail.polis.service.boriskin.ReplicaWorker.PROXY_HEADER_VALUE;

final class FuturesWorker {
    private static final Logger logger = LoggerFactory.getLogger(FuturesWorker.class);

    private FuturesWorker() {
        // to silence CodeClimate warning
    }

    /**
     * Отправляет соответствующие запросы на реплики.
     *
     * @param replicas реплики
     * @param mir mir info запроса
     * @return если acks = 0, то пустой набор. Иначе - набор фьюч
     */
    static CompletableFuture<List<HttpResponse<byte[]>>> getResponses(
            @NotNull final List<String> replicas,
            @NotNull final MetaInfoRequest mir,
            @NotNull final Topology<String> topology,
            @NotNull final HttpClient javaNetHttpClient,
            @NotNull final HttpSession httpSession) {
        final int acks;
        if (replicas.contains(topology.recogniseMyself())) {
            acks = mir.getReplicaFactor().getAck() - 1;
        } else {
            acks = mir.getReplicaFactor().getAck();
        }

        if (acks > 0) {
            final ArrayList<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
            for (final String node : replicas) {
                if (!topology.isMyNode(node)) {
                    final HttpRequest.Builder requestBuilder =
                            HttpRequest
                                    .newBuilder(URI.create(node + mir.getRequest().getURI()))
                                    .setHeader(PROXY_HEADER_NAME, PROXY_HEADER_VALUE);
                    final byte[] body = mir.getRequest().getBody();
                    switch (mir.getMethod()) {
                        case GET:
                            requestBuilder.GET();
                            break;
                        case PUT:
                            requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                            break;
                        case DELETE:
                            requestBuilder.DELETE();
                            break;
                        default:
                            throw new IllegalArgumentException("Непонятный метод");
                    }
                    futures.add(
                            javaNetHttpClient.sendAsync(
                                    requestBuilder.build(),
                                    HttpResponse.BodyHandlers.ofByteArray()));
                }
            }
            return getListOfReceivedResponses(futures, acks, httpSession);
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private static CompletableFuture<List<HttpResponse<byte[]>>> getListOfReceivedResponses(
            @NotNull final List<CompletableFuture<HttpResponse<byte[]>>> futures,
            final int acks,
            @NotNull final HttpSession httpSession) {
        if (futures.size() < acks) {
            throw new IllegalArgumentException(
                    "Запросов ожидалось: " + futures.size()
                            + ", acks: " + acks);
        }
        final int errorsThreshold = futures.size() - acks;
        final AtomicInteger errors = new AtomicInteger(0);

        final CopyOnWriteArrayList<HttpResponse<byte[]>> responses =
                new CopyOnWriteArrayList<>();
        final CompletableFuture<List<HttpResponse<byte[]>>> result =
                new CompletableFuture<>();

        final BiConsumer<HttpResponse<byte[]>, Throwable> biConsumer = (value, error) -> {
            if ((error != null || value == null)
                    && errors.incrementAndGet() > errorsThreshold) {
                result.complete(responses);
            } else if (!result.isDone() && value != null) {
                responses.add(value);
                if (responses.size() >= acks) {
                    result.complete(responses);
                }
            }
        };
        futures.forEach(
                future -> future
                        .orTimeout(1, TimeUnit.SECONDS)
                        .whenCompleteAsync(biConsumer)
                        .exceptionally(exception -> {
                            logger.error("Ошибка при использовании Future: ", exception);
                            return null;
                        }));

        try {
            for (final HttpResponse<byte[]> response : result.get()) {
                if (response.statusCode() == 404) {
                    resp(httpSession, new Response(Response.NOT_FOUND, Response.EMPTY));
                    return null;
                }
            }
        } catch (InterruptedException | ExecutionException exception) {
            logger.error("Ошибка при return 404: ", exception);
        }

        return result;
    }
}
