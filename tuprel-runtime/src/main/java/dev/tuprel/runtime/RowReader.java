package dev.tuprel.runtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/** Typed, nullable reads from the current row; valid only during a mapper call. */
public interface RowReader {
    String text(String column);
    Short int16(String column);
    Integer int32(String column);
    Long int64(String column);
    Float float32(String column);
    Double float64(String column);
    BigDecimal decimal(String column);
    Boolean bool(String column);
    UUID uuid(String column);
    Instant instant(String column);
    LocalDateTime dateTime(String column);
    LocalDate date(String column);
    LocalTime time(String column);
    byte[] bytes(String column);
}
