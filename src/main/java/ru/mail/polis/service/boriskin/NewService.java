package ru.mail.polis.service.boriskin;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import one.nio.http.*;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.boriskin.Topology;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Поддержка следующего HTTP REST API протокола:
 * 1. HTTP GET /v0/entity?id="ID" -- получить данные по ключу.
 * Возвращает 200 OK и данные или 404 Not Found
 * 2. HTTP PUT /v0/entity?id="ID" -- создать/перезаписать (upsert) данные по ключу.
 * Возвращает 201 Created
 * 3. HTTP DELETE /v0/entity?id="ID" -- удалить данные по ключу.
 * Возвращает 202 Accepted
 *
 * @author makary
 */
public class NewService extends HttpServer implements Service {
    private static final Logger logger = LoggerFactory.getLogger(NewService.class);

    @NotNull
    private final ExecutorService executorService;

    @NotNull
    private final DAO dao;
    @NotNull
    private final Topology<String> topology;
    @NotNull
    private final Map<String, HttpClient> nodeToClientMap;

    private enum OPERATIONS{ GETTING, UPSERTING, REMOVING };

    /**
     * Конструктор {@link NewService}.
     *
     * @param port порт
     * @param dao Дао
     * @param workers воркеры
     * @param queueSize размер очереди
     * @throws IOException возможна ошибка при неправильных параметрах
     */
    public NewService(
            final int port,
            @NotNull final DAO dao,
            final int workers,
            final int queueSize,
            @NotNull final Topology<String> topology) throws IOException {
        super(getConfigFrom(port));
        assert 0 < workers;
        assert 0 < queueSize;
        this.dao = dao;
        this.executorService = new ThreadPoolExecutor(
                workers,
                workers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactoryBuilder()
                        .setNameFormat("worker-%d")
                        .setUncaughtExceptionHandler((t, e) ->
                                logger.error("Ошибка в {}, возникла при обработке запроса", t, e))
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
        this.topology = topology;
        this.nodeToClientMap = new HashMap<>();
        for (final String node : topology.all()) {
            if (topology.isMyNode(node)) {
                continue;
            }
            final HttpClient httpClient = new HttpClient(
                    new ConnectionString(node + "?timeout=1000"));
            if (nodeToClientMap.put(node, httpClient) != null) {
                throw new IllegalStateException("Duplicate Node!");
            }
        }
    }

    @NotNull
    private static HttpServerConfig getConfigFrom(final int port) {
        final AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.deferAccept = true;
        acceptorConfig.reusePort = true;
        final HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return httpServerConfig;
    }

    @NotNull
    private static byte[] toByteArray(@NotNull final ByteBuffer byteBuffer) {
        if (!byteBuffer.hasRemaining()) {
            return Response.EMPTY;
        }
        final byte[] result = new byte[byteBuffer.remaining()];
        byteBuffer.get(result);
        assert !byteBuffer.hasRemaining();
        return result;
    }

    /**
     * HTTP GET /v0/entity?id="ID" -- получает данные по ключу.
     *
     * @param id ключ
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public void get(
            @Param(value = "id", required = true) final String id,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request) {
        idValidation(id, httpSession);
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        operation(key, httpSession, request, OPERATIONS.GETTING);
    }

    /**
     * HTTP PUT /v0/entity?id="ID" -- создает/перезаписывает (upsert) данные по ключу.
     *
     * @param id ключ
     * @param request данные
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public void put(
            @Param(value = "id", required = true) final String id,
            final Request request,
            @NotNull final HttpSession httpSession
    ) {
        idValidation(id, httpSession);
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        operation(key, httpSession, request, OPERATIONS.UPSERTING);
    }

    /**
     * HTTP DELETE /v0/entity?id="ID" - удаляет данные по ключу.
     *
     * @param id ключ
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public void delete(
            @Param(value = "id", required = true) final String id,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request) {
        idValidation(id, httpSession);
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        operation(key, httpSession, request, OPERATIONS.REMOVING);
    }

    private void idValidation(
            @Param(value = "id", required = true) final String id,
            @NotNull final HttpSession httpSession) {
        if (id.isEmpty()) {
            try {
                httpSession.sendResponse(resp(Response.BAD_REQUEST));
            } catch (IOException ioException) {
                logger.error("Не плучается отправить запрос", ioException);
            }
        }
    }

    private void operation(
            @NotNull final ByteBuffer key,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request,
            @NotNull final OPERATIONS operation) {
        executorService.execute(() -> {
            try {
                switch (operation) {
                    case GETTING:
                        doGet(key, httpSession, request);
                        break;
                    case UPSERTING:
                        final ByteBuffer value = ByteBuffer.wrap(request.getBody());
                        doUpsert(key, httpSession, request, value);
                        break;
                    case REMOVING:
                        doRemove(key, httpSession, request);
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + operation);
                }
            } catch (IOException ioException) {
                logger.error("Ошибка в {}: ", operation, ioException);
            }
        });
    }

    private void doGet(
            @NotNull final ByteBuffer key,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request) throws IOException {
        final String node = topology.primaryFor(key);
        // Локальный ли запрос?
        if (topology.isMyNode(node)) {
            try {
                httpSession.sendResponse(
                        Response.ok(
                                toByteArray(dao.get(key)))
                );
            } catch (NoSuchElementException noSuchElementException) {
                httpSession.sendResponse(resp(Response.NOT_FOUND));
            } catch (IOException ioException) {
                logger.error("Ошибка в GET: {}", toByteArray(key));
                httpSession.sendResponse(resp(Response.INTERNAL_ERROR));
            }
        } else {
            proxy(node, httpSession, request);
        }
    }

    private void proxy(
            @NotNull final String node,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request) throws IOException {
        try {
            request.addHeader("X-Proxy-For: " + node);
            httpSession.sendResponse(nodeToClientMap.get(node).invoke(request));
        } catch (IOException | InterruptedException | HttpException | PoolException exception) {
            logger.error("Can't proxy request", exception);
            httpSession.sendResponse(resp(Response.INTERNAL_ERROR));
        }
    }

    private void doUpsert(
            @NotNull final ByteBuffer key,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request,
            final ByteBuffer value) throws IOException {
        final String node = topology.primaryFor(key);
        // Локальный ли запрос?
        if (topology.isMyNode(node)) {
            try {
                dao.upsert(key, value);
                httpSession.sendResponse(resp(Response.CREATED));
            } catch (IOException ioException) {
                logger.error("Ошибка в PUT: {}, значение: {}", toByteArray(key), toByteArray(value));
                httpSession.sendResponse(resp(Response.INTERNAL_ERROR));
            }
        } else {
            proxy(node, httpSession, request);
        }
    }

    private void doRemove(
            @NotNull final ByteBuffer key,
            @NotNull final HttpSession httpSession,
            @NotNull final Request request) throws IOException {
        final String node = topology.primaryFor(key);
        // Локальный ли запрос?
        if (topology.isMyNode(node)) {
            try {
                dao.remove(key);
                httpSession.sendResponse(
                        resp(Response.ACCEPTED)
                );
            } catch (IOException ioException) {
                logger.error("Ошибка в DELETE: {}", toByteArray(key));
                httpSession.sendResponse(resp(Response.INTERNAL_ERROR));
            }
        } else {
            proxy(node, httpSession, request);
        }
    }

    /**
     * Метод проверки: отвечает ли сервер.
     *
     * @param httpSession сессия
     */
    @Path("/v0/status")
    public void status(final HttpSession httpSession) {
        executorService.execute(() -> {
            try {
                httpSession.sendResponse(Response.ok("OK"));
            } catch (IOException ioException) {
                logger.error("Ошибка при ответе", ioException);
            }
        });
    }

    /**
     * Логирует непонятные запросы.
     *
     * @param request Запрос
     * @param httpSession Диалоговое состояние
     * @throws IOException Возможна проблема ввода-вывода
     */
    @Override
    public void handleDefault(
            final Request request,
            final HttpSession httpSession
    ) throws IOException {
        logger.error("Непонятный запрос: {}", request);
        httpSession.sendResponse(resp(Response.BAD_REQUEST));
    }

    private Response resp(final String response) {
        return new Response(response, Response.EMPTY);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            logger.error("Не получилось завершить Executor Service", interruptedException);
            Thread.currentThread().interrupt();
        }
        for (final HttpClient httpClient : nodeToClientMap.values()) {
            httpClient.clear();
        }
    }
}
