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

import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Value;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public class SpannerTypeUtilsTest {

    // ── NULL ──────────────────────────────────────────────────────────────────

    @Test
    public void testNullValueReturnsNull() {
        Assertions.assertNull(SpannerTypeUtils.getValue(Value.bool(null)));
    }

    @Test
    public void testNullArgumentReturnsNull() {
        Assertions.assertNull(SpannerTypeUtils.getValue(null));
    }

    // ── BOOL ──────────────────────────────────────────────────────────────────

    @Test
    public void testBoolTrue() {
        Assertions.assertEquals(Boolean.TRUE, SpannerTypeUtils.getValue(Value.bool(true)));
    }

    @Test
    public void testBoolFalse() {
        Assertions.assertEquals(Boolean.FALSE, SpannerTypeUtils.getValue(Value.bool(false)));
    }

    // ── INT64 ─────────────────────────────────────────────────────────────────

    @Test
    public void testInt64() {
        Assertions.assertEquals(42L, SpannerTypeUtils.getValue(Value.int64(42L)));
    }

    @Test
    public void testInt64Negative() {
        Assertions.assertEquals(-1L, SpannerTypeUtils.getValue(Value.int64(-1L)));
    }

    // ── FLOAT64 ───────────────────────────────────────────────────────────────

    @Test
    public void testFloat64() {
        Assertions.assertEquals(3.14, (Double) SpannerTypeUtils.getValue(Value.float64(3.14)), 1e-10);
    }

    // ── NUMERIC ───────────────────────────────────────────────────────────────

    @Test
    public void testNumeric() {
        BigDecimal bd = new BigDecimal("123456789.987654321");
        Assertions.assertEquals(bd, SpannerTypeUtils.getValue(Value.numeric(bd)));
    }

    // ── STRING ────────────────────────────────────────────────────────────────

    @Test
    public void testString() {
        Assertions.assertEquals("hello", SpannerTypeUtils.getValue(Value.string("hello")));
    }

    @Test
    public void testEmptyString() {
        Assertions.assertEquals("", SpannerTypeUtils.getValue(Value.string("")));
    }

    // ── BYTES ─────────────────────────────────────────────────────────────────

    @Test
    public void testBytes() {
        byte[] original = new byte[]{0x01, 0x02, 0x03};
        com.google.cloud.spanner.ByteArray ba = com.google.cloud.spanner.ByteArray.copyFrom(original);
        Object result = SpannerTypeUtils.getValue(Value.bytes(ba));
        Assertions.assertInstanceOf(byte[].class, result);
        Assertions.assertArrayEquals(original, (byte[]) result);
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    @Test
    public void testJson() {
        Assertions.assertEquals("{\"k\":1}", SpannerTypeUtils.getValue(Value.json("{\"k\":1}")));
    }

    // ── DATE ──────────────────────────────────────────────────────────────────

    @Test
    public void testDate() {
        Date d = Date.fromYearMonthDay(2023, 7, 4);
        Object result = SpannerTypeUtils.getValue(Value.date(d));
        Assertions.assertInstanceOf(LocalDate.class, result);
        LocalDate ld = (LocalDate) result;
        Assertions.assertEquals(2023, ld.getYear());
        Assertions.assertEquals(7, ld.getMonthValue());
        Assertions.assertEquals(4, ld.getDayOfMonth());
    }

    // ── TIMESTAMP ─────────────────────────────────────────────────────────────

    @Test
    public void testTimestamp() {
        Timestamp ts = Timestamp.ofTimeSecondsAndNanos(1688468400L, 0);
        Object result = SpannerTypeUtils.getValue(Value.timestamp(ts));
        Assertions.assertInstanceOf(LocalDateTime.class, result);
    }

    // ── ARRAY ─────────────────────────────────────────────────────────────────

    @Test
    public void testArrayOfStrings() {
        Value v = Value.stringArray(Arrays.asList("a", "b", "c"));
        Object result = SpannerTypeUtils.getValue(v);
        Assertions.assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        Assertions.assertEquals(3, list.size());
        Assertions.assertEquals("a", list.get(0));
    }

    @Test
    public void testArrayOfInt64() {
        Value v = Value.int64Array(new long[]{1L, 2L, 3L});
        Object result = SpannerTypeUtils.getValue(v);
        Assertions.assertInstanceOf(List.class, result);
        Assertions.assertEquals(3, ((List<?>) result).size());
    }

    @Test
    public void testArrayOfBool() {
        Value v = Value.boolArray(Arrays.asList(true, false, true));
        Object result = SpannerTypeUtils.getValue(v);
        List<?> list = (List<?>) result;
        Assertions.assertEquals(Boolean.TRUE,  list.get(0));
        Assertions.assertEquals(Boolean.FALSE, list.get(1));
    }
}
