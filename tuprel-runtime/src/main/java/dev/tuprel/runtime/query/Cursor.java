package dev.tuprel.runtime.query;

import dev.tuprel.sql.SqlValue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque position in a cursor-paginated result.
 *
 * <p>{@link #encode()} produces a URL-safe token that can travel through an HTTP API and be
 * restored with {@link #decode(String)}. The token holds the ordering key values of the last row
 * of a page and the identity of the ordering; it is neither encrypted nor signed. A client can
 * craft a token, but its values are only ever bound as statement parameters, and a token whose
 * ordering, types or structure do not match the query is rejected. A cursor is a position, not
 * an authorization token: access rules belong in the query condition.
 */
public final class Cursor {
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private static final int MAX_VALUES = 32;
    private static final int VERSION = 1;

    private final String ordering;
    private final List<SqlValue> values;

    Cursor(String ordering, List<SqlValue> values) {
        this.ordering = Objects.requireNonNull(ordering, "ordering");
        this.values = List.copyOf(values);
        if (this.values.isEmpty() || this.values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        for (SqlValue value : this.values) {
            typeOf(value);
        }
    }

    /** Restores a cursor from {@link #encode()} output; malformed tokens are rejected. */
    public static Cursor decode(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readUnsignedByte() != VERSION) {
                throw invalid();
            }
            String ordering = readText(input);
            int count = input.readUnsignedByte();
            if (count == 0 || count > MAX_VALUES) {
                throw invalid();
            }
            List<SqlValue> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                values.add(readValue(input));
            }
            if (input.available() != 0) {
                throw invalid();
            }
            return new Cursor(ordering, values);
        } catch (IOException | RuntimeException exception) {
            // The cause may echo attacker-controlled content, so it is not propagated.
            throw invalid();
        }
    }

    /** URL-safe, unpadded Base64 token. */
    public String encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(VERSION);
            writeText(output, ordering);
            output.writeByte(values.size());
            for (SqlValue value : values) {
                writeValue(output, value);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cursor cursor && ordering.equals(cursor.ordering)
                && values.equals(cursor.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ordering, values);
    }

    @Override
    public String toString() {
        return "Cursor[redacted]";
    }

    String ordering() {
        return ordering;
    }

    List<SqlValue> values() {
        return values;
    }

    static SqlValue.Type typeOf(SqlValue value) {
        Objects.requireNonNull(value);
        if (value instanceof SqlValue.Text) {
            return SqlValue.Type.TEXT;
        }
        if (value instanceof SqlValue.Int16) {
            return SqlValue.Type.INT16;
        }
        if (value instanceof SqlValue.Int32) {
            return SqlValue.Type.INT32;
        }
        if (value instanceof SqlValue.Int64) {
            return SqlValue.Type.INT64;
        }
        if (value instanceof SqlValue.Float32) {
            return SqlValue.Type.FLOAT32;
        }
        if (value instanceof SqlValue.Float64) {
            return SqlValue.Type.FLOAT64;
        }
        if (value instanceof SqlValue.Decimal) {
            return SqlValue.Type.DECIMAL;
        }
        if (value instanceof SqlValue.Bool) {
            return SqlValue.Type.BOOLEAN;
        }
        if (value instanceof SqlValue.Uuid) {
            return SqlValue.Type.UUID;
        }
        if (value instanceof SqlValue.Timestamp) {
            return SqlValue.Type.INSTANT;
        }
        if (value instanceof SqlValue.DateTime) {
            return SqlValue.Type.LOCAL_DATE_TIME;
        }
        if (value instanceof SqlValue.Date) {
            return SqlValue.Type.LOCAL_DATE;
        }
        if (value instanceof SqlValue.Time) {
            return SqlValue.Type.LOCAL_TIME;
        }
        if (value instanceof SqlValue.Binary) {
            return SqlValue.Type.BYTES;
        }
        if (value instanceof SqlValue.Null) {
            throw new IllegalArgumentException("Cursor values cannot be NULL");
        }
        throw new IncompatibleClassChangeError();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid cursor");
    }

    /** Stable wire tag per type; never derived from enum ordinals. */
    private static int tag(SqlValue.Type type) {
        return switch (type) {
            case TEXT -> 1;
            case INT16 -> 2;
            case INT32 -> 3;
            case INT64 -> 4;
            case FLOAT32 -> 5;
            case FLOAT64 -> 6;
            case DECIMAL -> 7;
            case BOOLEAN -> 8;
            case UUID -> 9;
            case INSTANT -> 10;
            case LOCAL_DATE_TIME -> 11;
            case LOCAL_DATE -> 12;
            case LOCAL_TIME -> 13;
            case BYTES -> 14;
        };
    }

    private static void writeValue(DataOutputStream output, SqlValue value) throws IOException {
        output.writeByte(tag(typeOf(value)));
        if (value instanceof SqlValue.Text item) {
            writeText(output, item.value());
        } else if (value instanceof SqlValue.Int16 item) {
            output.writeShort(item.value());
        } else if (value instanceof SqlValue.Int32 item) {
            output.writeInt(item.value());
        } else if (value instanceof SqlValue.Int64 item) {
            output.writeLong(item.value());
        } else if (value instanceof SqlValue.Float32 item) {
            output.writeInt(Float.floatToIntBits(item.value()));
        } else if (value instanceof SqlValue.Float64 item) {
            output.writeLong(Double.doubleToLongBits(item.value()));
        } else if (value instanceof SqlValue.Decimal item) {
            writeText(output, item.value().toString());
        } else if (value instanceof SqlValue.Bool item) {
            output.writeBoolean(item.value());
        } else if (value instanceof SqlValue.Uuid item) {
            output.writeLong(item.value().getMostSignificantBits());
            output.writeLong(item.value().getLeastSignificantBits());
        } else if (value instanceof SqlValue.Timestamp item) {
            writeInstant(output, item.value());
        } else if (value instanceof SqlValue.DateTime item) {
            output.writeLong(item.value().toLocalDate().toEpochDay());
            output.writeLong(item.value().toLocalTime().toNanoOfDay());
        } else if (value instanceof SqlValue.Date item) {
            output.writeLong(item.value().toEpochDay());
        } else if (value instanceof SqlValue.Time item) {
            output.writeLong(item.value().toNanoOfDay());
        } else if (value instanceof SqlValue.Binary item) {
            writeBytes(output, item.value());
        } else {
            throw new IncompatibleClassChangeError();
        }
    }

    private static SqlValue readValue(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new SqlValue.Text(readText(input));
            case 2 -> new SqlValue.Int16(input.readShort());
            case 3 -> new SqlValue.Int32(input.readInt());
            case 4 -> new SqlValue.Int64(input.readLong());
            case 5 -> new SqlValue.Float32(Float.intBitsToFloat(input.readInt()));
            case 6 -> new SqlValue.Float64(Double.longBitsToDouble(input.readLong()));
            case 7 -> new SqlValue.Decimal(new BigDecimal(readText(input)));
            case 8 -> switch (input.readUnsignedByte()) {
                case 0 -> new SqlValue.Bool(false);
                case 1 -> new SqlValue.Bool(true);
                default -> throw invalid();
            };
            case 9 -> new SqlValue.Uuid(new UUID(input.readLong(), input.readLong()));
            case 10 -> new SqlValue.Timestamp(Instant.ofEpochSecond(input.readLong(), input.readInt()));
            case 11 -> new SqlValue.DateTime(LocalDateTime.of(
                    LocalDate.ofEpochDay(input.readLong()), LocalTime.ofNanoOfDay(input.readLong())));
            case 12 -> new SqlValue.Date(LocalDate.ofEpochDay(input.readLong()));
            case 13 -> new SqlValue.Time(LocalTime.ofNanoOfDay(input.readLong()));
            case 14 -> new SqlValue.Binary(readBytes(input));
            default -> throw invalid();
        };
    }

    private static void writeInstant(DataOutputStream output, Instant instant) throws IOException {
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();
        output.writeLong(seconds);
        output.writeInt(nanos);
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(DataInputStream input) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(input)))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw invalid();
        }
        return input.readNBytes(length);
    }
}
