package ru.mail.polis.service.boriskin;

import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.mail.polis.dao.boriskin.TableCell;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

final class Value implements Comparable<Value> {
    private static final String TIMESTAMP_HEADER = "X-OK-Timestamp: ";

    private static final Value ABSENT =
            new Value(null, -1, State.ABSENT);

    @Nullable
    private final byte[] data;

    private final long timeStamp;

    @NotNull
    private final State state;

    private enum State {
        PRESENT, REMOVED, ABSENT
    }

    private Value(
            @Nullable final byte[] data,
            final long timeStamp,
            @NotNull final State state) {
        this.data =
                data == null
                ? null : Arrays.copyOf(data, data.length);
        this.timeStamp = timeStamp;
        this.state = state;
    }

    @NotNull
    static Value isPresent(
            @NotNull final byte[] data,
            final long timeStamp) {
        return new Value(data, timeStamp, State.PRESENT);
    }

    @NotNull
    static Value wasRemoved(
            final long timeStamp) {
        return new Value(null, Math.abs(timeStamp), State.REMOVED);
    }

    @NotNull
    static Value isAbsent() {
        return ABSENT;
    }

    @NotNull
    public static Value from(@NotNull final Response response) {
        final String timeStamp = response.getHeader(TIMESTAMP_HEADER);

        switch (response.getStatus()) {
            case 200:
                if (timeStamp == null) {
                    throw new IllegalArgumentException("Неверные данные на вход");
                }
                return isPresent(
                        response.getBody(),
                        Long.parseLong(timeStamp));
            case 404:
                if (timeStamp == null) {
                    return isAbsent();
                } else {
                    return wasRemoved(Long.parseLong(timeStamp));
                }
            default:
                throw new IllegalArgumentException("Неподходящий ответ");
        }
    }

    @NotNull
    public static Value from(
            @Nullable final TableCell tableCell) {
        if (tableCell == null) {
            return Value.isAbsent();
        }

        if (tableCell.getVal().wasRemoved()) {
            return Value.wasRemoved(tableCell.getVal().getTimeStamp());
        } else {
            final ByteBuffer data = tableCell.getVal().getData();
            final byte[] buffer = new byte[data.remaining()];

            data.duplicate().get(buffer);
            return
                    Value.isPresent(
                            buffer,
                            tableCell.getVal().getTimeStamp());
        }
    }

    @NotNull
    public static Response transform(
            @NotNull final Value value,
            final boolean alreadyProxied) {
        Response result;
        switch (value.getState()) {
            case PRESENT:
                result =
                        value.getData() == null
                                ? new Response(Response.NOT_FOUND, Response.EMPTY) :
                                new Response(Response.OK, value.getData());
                if (alreadyProxied) {
                    result.addHeader(TIMESTAMP_HEADER + value.getTimeStamp());
                }
                return result;
            case REMOVED:
                result = new Response(Response.NOT_FOUND, Response.EMPTY);
                if (alreadyProxied) {
                    result.addHeader(TIMESTAMP_HEADER + value.getTimeStamp());
                }
                return result;
            case ABSENT:
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            default:
                throw new IllegalArgumentException("Неверные данные на вход");
        }
    }

    static Value merge(
            @NotNull final Collection<Value> values) {
        return values.stream()
                .filter(v -> v.getState() != State.ABSENT)
                .max(Value::compareTo)
                .orElseGet(Value::isAbsent);
    }

    @Nullable
    byte[] getData() {
        if (data == null) {
            return null;
        }
        return Arrays.copyOf(data, data.length);
    }

    long getTimeStamp() {
        return timeStamp;
    }

    @NotNull
    State getState() {
        return state;
    }

    @Override
    public int compareTo(
            @NotNull final Value value) {
        return Long.compare(
                Math.abs(timeStamp),
                Math.abs(value.timeStamp));
    }
}
