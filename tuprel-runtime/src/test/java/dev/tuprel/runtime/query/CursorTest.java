package dev.tuprel.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.sql.SqlValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {
    @Test
    void roundTripsEveryOrderableValueTypeThroughAUrlSafeToken() {
        List<SqlValue> values = List.of(new SqlValue.Text("naïve ☃ '; DROP TABLE x;--"),
                new SqlValue.Int16((short) -7), new SqlValue.Int32(Integer.MIN_VALUE),
                new SqlValue.Int64(Long.MAX_VALUE), new SqlValue.Float32(1.5f),
                new SqlValue.Float64(-0.25), new SqlValue.Decimal(new BigDecimal("12345.6789")),
                new SqlValue.Bool(true), new SqlValue.Uuid(UUID.fromString("e1642970-ad35-45d0-9be0-69d6cd20b607")),
                new SqlValue.Timestamp(Instant.parse("2026-09-29T10:15:30.123456789Z")),
                new SqlValue.DateTime(LocalDateTime.parse("2026-09-29T10:15:30.5")),
                new SqlValue.Date(LocalDate.parse("1999-12-31")), new SqlValue.Time(LocalTime.parse("23:59:59.999")),
                new SqlValue.Binary(new byte[] {0, -1, 7}));
        Cursor cursor = new Cursor("Order:id+", values);
        String token = cursor.encode();
        assertTrue(token.matches("[A-Za-z0-9_-]+"), token);
        Cursor decoded = Cursor.decode(token);
        assertEquals(cursor, decoded);
        assertEquals(values, decoded.values());
        assertEquals("Order:id+", decoded.ordering());
        assertEquals(token, decoded.encode());
    }

    @Test
    void neverDisclosesValuesThroughToString() {
        Cursor cursor = new Cursor("User:email+,id+", List.of(new SqlValue.Text("secret@example.com"),
                new SqlValue.Int32(1)));
        assertFalse(cursor.toString().contains("secret"));
        assertFalse(cursor.toString().contains("email"));
    }

    @Test
    void rejectsMalformedHostileAndOversizedTokensWithoutEchoingThem() {
        String valid = new Cursor("T:id+", List.of(new SqlValue.Int32(5))).encode();
        byte[] bytes = Base64.getUrlDecoder().decode(valid);
        List<String> hostile = List.of(
                "",
                "not base64 !!",
                "' OR 1=1 --",
                valid.substring(0, valid.length() - 2),
                valid + "AA",
                encode(replace(bytes, 0, (byte) 2)),
                encode(replace(bytes, bytes.length - 5, (byte) 99)),
                encode(new byte[] {1, 0, 0, 0, 1, 'x', 1, 1, 0x7f, 0x7f, 0x7f, 0x7f}),
                encode(new byte[] {1, 0, 0, 0, 1, 'x', 1, 8, 2}),
                encode(new byte[] {1, 0, 0, 0, 1, 'x', 0}),
                encode(new byte[] {1, 0, 0, 0, 2, (byte) 0xC3, (byte) 0x28, 1, 3, 0, 0, 0, 1}),
                encode(new byte[] {1, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}),
                "A".repeat(20_000));
        for (String token : hostile) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> Cursor.decode(token), token);
            assertEquals("Invalid cursor", exception.getMessage());
            assertEquals(null, exception.getCause());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Cursor("T:id+", List.of(new SqlValue.Null(SqlValue.Type.INT32))));
        assertThrows(IllegalArgumentException.class, () -> new Cursor("T:id+", List.of()));
    }

    @Test
    void tokenFormatIsStableAcrossReleases() {
        Cursor cursor = new Cursor("T:id+", List.of(new SqlValue.Int32(5)));
        byte[] expected = {1, 0, 0, 0, 5, 'T', ':', 'i', 'd', '+', 1, 3, 0, 0, 0, 5};
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(expected), cursor.encode());
        assertEquals("T:id+", new String(Base64.getUrlDecoder().decode(cursor.encode()), 5, 5,
                StandardCharsets.UTF_8));
    }

    private static byte[] replace(byte[] bytes, int index, byte value) {
        byte[] copy = bytes.clone();
        copy[index] = value;
        return copy;
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
