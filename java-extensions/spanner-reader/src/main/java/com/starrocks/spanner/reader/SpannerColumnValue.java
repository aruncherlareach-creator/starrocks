// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.spanner.reader;

import com.starrocks.jni.connector.ColumnType;
import com.starrocks.jni.connector.ColumnValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Adapts a Java value (as produced by {@link SpannerTypeUtils#getValue}) to the
 * {@link ColumnValue} interface consumed by the off-heap table writer.
 */
public class SpannerColumnValue implements ColumnValue {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Spanner uses RFC 3339 nanosecond format: "2023-01-15T10:30:00.123456789Z". */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Object value;

    public SpannerColumnValue(Object value) {
        this.value = value;
    }

    @Override
    public boolean getBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    public byte getByte() {
        return ((Number) value).byteValue();
    }

    @Override
    public short getShort() {
        return ((Number) value).shortValue();
    }

    @Override
    public int getInt() {
        return ((Number) value).intValue();
    }

    @Override
    public float getFloat() {
        return ((Number) value).floatValue();
    }

    @Override
    public long getLong() {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        // INT64 is encoded as a decimal string in the Spanner gRPC wire format
        return Long.parseLong(value.toString());
    }

    @Override
    public double getDouble() {
        return ((Number) value).doubleValue();
    }

    @Override
    public BigDecimal getDecimal() {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    @Override
    public String getString(ColumnType.TypeValue type) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    @Override
    public byte[] getBytes() {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public LocalDate getDate() {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        // Spanner DATE wire format: "YYYY-MM-DD"
        try {
            return LocalDate.parse(value.toString(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(value.toString());
        }
    }

    @Override
    public LocalDateTime getDateTime(ColumnType.TypeValue type) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Long) {
            long micros = (Long) value;
            long seconds = micros / 1_000_000L;
            int nanos = (int) ((micros % 1_000_000L) * 1_000L);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
        }
        // Spanner TIMESTAMP wire format: RFC 3339 e.g. "2023-01-15T10:30:00.000000000Z"
        String s = value.toString();
        try {
            return LocalDateTime.ofInstant(Instant.from(TS_FMT.parse(s)), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(s);
        }
    }

    @Override
    public void unpackArray(List<ColumnValue> values) {
        if (value instanceof List) {
            for (Object elem : (List<?>) value) {
                values.add(elem != null ? new SpannerColumnValue(elem) : null);
            }
        }
    }

    @Override
    public void unpackMap(List<ColumnValue> keys, List<ColumnValue> values) {
        throw new UnsupportedOperationException("Spanner MAP type unpacking is not supported");
    }

    @Override
    public void unpackStruct(List<Integer> structFieldIndex, List<ColumnValue> values) {
        if (value instanceof List) {
            List<?> fields = (List<?>) value;
            for (int idx : structFieldIndex) {
                Object fieldVal = (idx < fields.size()) ? fields.get(idx) : null;
                values.add(fieldVal != null ? new SpannerColumnValue(fieldVal) : null);
            }
        }
    }
}
