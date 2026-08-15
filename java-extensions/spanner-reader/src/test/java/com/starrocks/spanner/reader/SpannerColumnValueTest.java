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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SpannerColumnValueTest {

    // ── Boolean ───────────────────────────────────────────────────────────────────

    @Test
    public void testGetBooleanFromBoolean() {
        Assertions.assertTrue(new SpannerColumnValue(Boolean.TRUE).getBoolean());
        Assertions.assertFalse(new SpannerColumnValue(Boolean.FALSE).getBoolean());
    }

    @Test
    public void testGetBooleanFromString() {
        Assertions.assertTrue(new SpannerColumnValue("true").getBoolean());
        Assertions.assertFalse(new SpannerColumnValue("false").getBoolean());
    }

    // ── Integer types ─────────────────────────────────────────────────────────────

    @Test
    public void testGetLongFromLong() {
        Assertions.assertEquals(42L, new SpannerColumnValue(42L).getLong());
    }

    @Test
    public void testGetLongFromString() {
        // Spanner encodes INT64 as decimal string
        Assertions.assertEquals(9_876_543_210L, new SpannerColumnValue("9876543210").getLong());
    }

    @Test
    public void testGetLongNegative() {
        Assertions.assertEquals(-1L, new SpannerColumnValue("-1").getLong());
    }

    @Test
    public void testGetIntFromLong() {
        Assertions.assertEquals(7, new SpannerColumnValue(7L).getInt());
    }

    @Test
    public void testGetShortFromLong() {
        Assertions.assertEquals((short) 3, new SpannerColumnValue(3L).getShort());
    }

    @Test
    public void testGetByteFromLong() {
        Assertions.assertEquals((byte) 100, new SpannerColumnValue(100L).getByte());
    }

    // ── Floating point ────────────────────────────────────────────────────────────

    @Test
    public void testGetDouble() {
        Assertions.assertEquals(2.718, new SpannerColumnValue(2.718).getDouble(), 1e-10);
    }

    @Test
    public void testGetFloat() {
        Assertions.assertEquals(1.5f, new SpannerColumnValue(1.5).getFloat(), 1e-6f);
    }

    // ── Decimal / NUMERIC ─────────────────────────────────────────────────────────

    @Test
    public void testGetDecimalFromBigDecimal() {
        BigDecimal bd = new BigDecimal("123.456789");
        Assertions.assertEquals(bd, new SpannerColumnValue(bd).getDecimal());
    }

    @Test
    public void testGetDecimalFromString() {
        BigDecimal expected = new BigDecimal("99999.000000001");
        Assertions.assertEquals(expected, new SpannerColumnValue("99999.000000001").getDecimal());
    }

    // ── String ────────────────────────────────────────────────────────────────────

    @Test
    public void testGetStringFromString() {
        Assertions.assertEquals("hello",
                new SpannerColumnValue("hello").getString(ColumnType.TypeValue.STRING));
    }

    @Test
    public void testGetStringFromLong() {
        Assertions.assertEquals("123",
                new SpannerColumnValue(123L).getString(ColumnType.TypeValue.STRING));
    }

    @Test
    public void testGetStringFromBytes() {
        byte[] bytes = "world".getBytes(StandardCharsets.UTF_8);
        String result = new SpannerColumnValue(bytes).getString(ColumnType.TypeValue.STRING);
        Assertions.assertEquals("world", result);
    }

    // ── Bytes ─────────────────────────────────────────────────────────────────────

    @Test
    public void testGetBytesFromByteArray() {
        byte[] b = new byte[] {1, 2, 3};
        Assertions.assertArrayEquals(b, new SpannerColumnValue(b).getBytes());
    }

    @Test
    public void testGetBytesFromString() {
        byte[] expected = "abc".getBytes(StandardCharsets.UTF_8);
        Assertions.assertArrayEquals(expected, new SpannerColumnValue("abc").getBytes());
    }

    // ── Date ──────────────────────────────────────────────────────────────────────

    @Test
    public void testGetDateFromLocalDate() {
        LocalDate d = LocalDate.of(2023, 7, 4);
        Assertions.assertEquals(d, new SpannerColumnValue(d).getDate());
    }

    @Test
    public void testGetDateFromString() {
        LocalDate expected = LocalDate.of(2023, 7, 4);
        Assertions.assertEquals(expected, new SpannerColumnValue("2023-07-04").getDate());
    }

    // ── DateTime / Timestamp ──────────────────────────────────────────────────────

    @Test
    public void testGetDateTimeFromLocalDateTime() {
        LocalDateTime dt = LocalDateTime.of(2023, 7, 4, 12, 30, 0);
        Assertions.assertEquals(dt,
                new SpannerColumnValue(dt).getDateTime(ColumnType.TypeValue.DATETIME));
    }

    @Test
    public void testGetDateTimeFromRfc3339String() {
        // Spanner TIMESTAMP wire format: RFC 3339
        LocalDateTime expected = LocalDateTime.of(2023, 7, 4, 12, 30, 0);
        String ts = "2023-07-04T12:30:00Z";
        LocalDateTime result = new SpannerColumnValue(ts)
                .getDateTime(ColumnType.TypeValue.DATETIME);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testGetDateTimeFromMicroseconds() {
        // 0 microseconds = epoch
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
        Assertions.assertEquals(epoch,
                new SpannerColumnValue(0L).getDateTime(ColumnType.TypeValue.DATETIME));
    }

    // ── Array / Struct ────────────────────────────────────────────────────────────

    @Test
    public void testUnpackArrayFromList() {
        List<Object> elements = Arrays.asList("a", "b", "c");
        List<com.starrocks.jni.connector.ColumnValue> out = new ArrayList<>();
        new SpannerColumnValue(elements).unpackArray(out);
        Assertions.assertEquals(3, out.size());
        Assertions.assertEquals("a",
                out.get(0).getString(ColumnType.TypeValue.STRING));
        Assertions.assertEquals("b",
                out.get(1).getString(ColumnType.TypeValue.STRING));
    }

    @Test
    public void testUnpackArrayWithNullElement() {
        List<Object> elements = new ArrayList<>();
        elements.add("x");
        elements.add(null);
        elements.add("z");
        List<com.starrocks.jni.connector.ColumnValue> out = new ArrayList<>();
        new SpannerColumnValue(elements).unpackArray(out);
        Assertions.assertEquals(3, out.size());
        Assertions.assertNull(out.get(1));
    }

    @Test
    public void testUnpackStructByIndex() {
        List<Object> fields = Arrays.asList("Alice", 30L, true);
        List<Integer> indices = Arrays.asList(0, 2);
        List<com.starrocks.jni.connector.ColumnValue> out = new ArrayList<>();
        new SpannerColumnValue(fields).unpackStruct(indices, out);
        Assertions.assertEquals(2, out.size());
        Assertions.assertEquals("Alice",
                out.get(0).getString(ColumnType.TypeValue.STRING));
        Assertions.assertTrue(out.get(1).getBoolean());
    }

    @Test
    public void testUnpackStructOutOfBoundsReturnsNull() {
        List<Object> fields = Arrays.asList("only");
        List<Integer> indices = Arrays.asList(0, 5 /* out of range */);
        List<com.starrocks.jni.connector.ColumnValue> out = new ArrayList<>();
        new SpannerColumnValue(fields).unpackStruct(indices, out);
        Assertions.assertEquals(2, out.size());
        Assertions.assertNotNull(out.get(0));
        Assertions.assertNull(out.get(1));
    }

    @Test
    public void testUnpackMapThrows() {
        Assertions.assertThrows(UnsupportedOperationException.class, () ->
                new SpannerColumnValue("anything").unpackMap(new ArrayList<>(), new ArrayList<>()));
    }
}
