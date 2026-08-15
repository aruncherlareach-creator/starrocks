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

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class SpannerTypeUtilsTest {

    // ── NULL ──────────────────────────────────────────────────────────────────────

    @Test
    public void testNullValueReturnsNull() {
        Value v = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        Assertions.assertNull(SpannerTypeUtils.getValue(v, false));
    }

    @Test
    public void testNullArgumentReturnsNull() {
        Assertions.assertNull(SpannerTypeUtils.getValue(null, false));
    }

    // ── BOOL ──────────────────────────────────────────────────────────────────────

    @Test
    public void testBoolTrue() {
        Value v = Value.newBuilder().setBoolValue(true).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals(Boolean.TRUE, result);
    }

    @Test
    public void testBoolFalse() {
        Value v = Value.newBuilder().setBoolValue(false).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals(Boolean.FALSE, result);
    }

    // ── FLOAT64 ───────────────────────────────────────────────────────────────────

    @Test
    public void testFloat64() {
        Value v = Value.newBuilder().setNumberValue(3.14159).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertInstanceOf(Double.class, result);
        Assertions.assertEquals(3.14159, (Double) result, 1e-10);
    }

    @Test
    public void testFloat64Zero() {
        Value v = Value.newBuilder().setNumberValue(0.0).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals(0.0, (Double) result, 1e-15);
    }

    // ── STRING / INT64 / DATE / TIMESTAMP / NUMERIC ───────────────────────────────

    @Test
    public void testStringValue() {
        Value v = Value.newBuilder().setStringValue("hello world").build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals("hello world", result);
    }

    @Test
    public void testInt64EncodedAsString() {
        // Spanner encodes INT64 as a string to avoid 64-bit precision issues in JSON
        Value v = Value.newBuilder().setStringValue("9876543210").build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals("9876543210", result);
    }

    @Test
    public void testDateString() {
        Value v = Value.newBuilder().setStringValue("2023-07-04").build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals("2023-07-04", result);
    }

    @Test
    public void testTimestampString() {
        Value v = Value.newBuilder().setStringValue("2023-07-04T12:30:00.000000000Z").build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals("2023-07-04T12:30:00.000000000Z", result);
    }

    @Test
    public void testNumericString() {
        Value v = Value.newBuilder().setStringValue("123456789.987654321").build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals("123456789.987654321", result);
    }

    // ── BYTES ──────────────────────────────────────────────────────────────────────

    @Test
    public void testBytesDecoded() {
        byte[] original = new byte[] {0x01, 0x02, 0x03, (byte) 0xFF};
        String encoded = Base64.getEncoder().encodeToString(original);
        Value v = Value.newBuilder().setStringValue(encoded).build();
        Object result = SpannerTypeUtils.getValue(v, true /* isBytes */);
        Assertions.assertInstanceOf(byte[].class, result);
        Assertions.assertArrayEquals(original, (byte[]) result);
    }

    @Test
    public void testBytesNotDecodedWhenFlagFalse() {
        byte[] original = new byte[] {0x01, 0x02};
        String encoded = Base64.getEncoder().encodeToString(original);
        Value v = Value.newBuilder().setStringValue(encoded).build();
        // With isBytes=false, string is returned as-is
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertInstanceOf(String.class, result);
        Assertions.assertEquals(encoded, result);
    }

    // ── ARRAY ──────────────────────────────────────────────────────────────────────

    @Test
    public void testArrayOfStrings() {
        Value v = Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("a"))
                .addValues(Value.newBuilder().setStringValue("b"))
                .addValues(Value.newBuilder().setStringValue("c"))
                .build()).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        Assertions.assertEquals(3, list.size());
        Assertions.assertEquals("a", list.get(0));
        Assertions.assertEquals("b", list.get(1));
        Assertions.assertEquals("c", list.get(2));
    }

    @Test
    public void testArrayWithNullElement() {
        Value v = Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("x"))
                .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
                .addValues(Value.newBuilder().setStringValue("z"))
                .build()).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        List<?> list = (List<?>) result;
        Assertions.assertEquals(3, list.size());
        Assertions.assertEquals("x", list.get(0));
        Assertions.assertNull(list.get(1));
        Assertions.assertEquals("z", list.get(2));
    }

    @Test
    public void testEmptyArray() {
        Value v = Value.newBuilder().setListValue(ListValue.newBuilder().build()).build();
        Object result = SpannerTypeUtils.getValue(v, false);
        Assertions.assertInstanceOf(List.class, result);
        Assertions.assertTrue(((List<?>) result).isEmpty());
    }

    @Test
    public void testArrayOfBools() {
        Value v = Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setBoolValue(true))
                .addValues(Value.newBuilder().setBoolValue(false))
                .build()).build();
        List<?> list = (List<?>) SpannerTypeUtils.getValue(v, false);
        Assertions.assertEquals(Boolean.TRUE,  list.get(0));
        Assertions.assertEquals(Boolean.FALSE, list.get(1));
    }
}
