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

package com.starrocks.connector.spanner;

import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import com.starrocks.type.ArrayType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.starrocks.type.BooleanType.BOOLEAN;
import static com.starrocks.type.DateType.DATE;
import static com.starrocks.type.DateType.DATETIME;
import static com.starrocks.type.FloatType.DOUBLE;
import static com.starrocks.type.IntegerType.BIGINT;
import static com.starrocks.type.VarbinaryType.VARBINARY;

public class SpannerSchemaUtilsTest {

    private Type spannerType(TypeCode code) {
        return Type.newBuilder().setCode(code).build();
    }

    private Type arrayOf(TypeCode elementCode) {
        return Type.newBuilder()
                .setCode(TypeCode.ARRAY)
                .setArrayElementType(spannerType(elementCode))
                .build();
    }

    @Test
    public void testBoolMapsToBoolean() {
        Assertions.assertEquals(BOOLEAN,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.BOOL)));
    }

    @Test
    public void testInt64MapsToBigint() {
        Assertions.assertEquals(BIGINT,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.INT64)));
    }

    @Test
    public void testFloat64MapsToDouble() {
        Assertions.assertEquals(DOUBLE,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.FLOAT64)));
    }

    @Test
    public void testStringMapsToVarchar() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.STRING));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testBytesMapsToVarbinary() {
        Assertions.assertEquals(VARBINARY,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.BYTES)));
    }

    @Test
    public void testDateMapsToDate() {
        Assertions.assertEquals(DATE,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.DATE)));
    }

    @Test
    public void testTimestampMapsToDatetime() {
        Assertions.assertEquals(DATETIME,
                SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.TIMESTAMP)));
    }

    @Test
    public void testNumericMapsToDecimal() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.NUMERIC));
        Assertions.assertTrue(t.isDecimalV3());
    }

    @Test
    public void testPgNumericMapsToDecimal() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.PG_NUMERIC));
        Assertions.assertTrue(t.isDecimalV3());
    }

    @Test
    public void testJsonMapsToJson() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.JSON));
        Assertions.assertTrue(t.isJsonType());
    }

    @Test
    public void testPgJsonbMapsToString() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.PG_JSONB));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testStructMapsToJson() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.STRUCT));
        Assertions.assertTrue(t.isJsonType());
    }

    @Test
    public void testArrayOfInt64MapsToArray() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(arrayOf(TypeCode.INT64));
        Assertions.assertTrue(t instanceof ArrayType);
        ArrayType at = (ArrayType) t;
        Assertions.assertEquals(BIGINT, at.getItemType());
    }

    @Test
    public void testArrayOfStringMapsToArray() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(arrayOf(TypeCode.STRING));
        Assertions.assertTrue(t instanceof ArrayType);
        ArrayType at = (ArrayType) t;
        Assertions.assertTrue(at.getItemType().isStringType());
    }

    @Test
    public void testArrayOfBoolMapsToArray() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(arrayOf(TypeCode.BOOL));
        Assertions.assertTrue(t instanceof ArrayType);
        ArrayType at = (ArrayType) t;
        Assertions.assertEquals(BOOLEAN, at.getItemType());
    }

    @Test
    public void testFloat32MapsToFloat() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.FLOAT32));
        Assertions.assertEquals(com.starrocks.type.FloatType.FLOAT, t);
    }

    @Test
    public void testProtoMapsToString() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.PROTO));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testEnumMapsToString() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.ENUM));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testIntervalMapsToString() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.INTERVAL));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testUuidMapsToString() {
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(spannerType(TypeCode.UUID));
        Assertions.assertTrue(t.isStringType());
    }

    @Test
    public void testUnknownTypeCodeMapsToString() {
        // TYPE_CODE_UNSPECIFIED should fall to default
        com.starrocks.type.Type t = SpannerSchemaUtils.spannerTypeToStarRocks(
                spannerType(TypeCode.TYPE_CODE_UNSPECIFIED));
        Assertions.assertTrue(t.isStringType());
    }
}
