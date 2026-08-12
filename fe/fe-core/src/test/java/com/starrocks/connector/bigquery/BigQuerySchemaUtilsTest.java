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

package com.starrocks.connector.bigquery;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.starrocks.type.ArrayType;
import com.starrocks.catalog.Column;
import com.starrocks.type.ScalarType;
import com.starrocks.type.StructType;
import com.starrocks.type.Type;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class BigQuerySchemaUtilsTest {

    private Field field(String name, StandardSQLTypeName type) {
        return Field.of(name, com.google.cloud.bigquery.StandardSQLTypeName.valueOf(type.name()));
    }

    private Field repeatedField(String name, StandardSQLTypeName type) {
        return Field.newBuilder(name, com.google.cloud.bigquery.StandardSQLTypeName.valueOf(type.name()))
                .setMode(Field.Mode.REPEATED)
                .build();
    }

    @Test
    public void testInt64MapsToLargint() {
        Schema schema = Schema.of(field("id", StandardSQLTypeName.INT64));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals(1, cols.size());
        Assertions.assertEquals(Type.BIGINT, cols.get(0).getType());
    }

    @Test
    public void testFloat64MapsToDouble() {
        Schema schema = Schema.of(field("val", StandardSQLTypeName.FLOAT64));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals(Type.DOUBLE, cols.get(0).getType());
    }

    @Test
    public void testBoolMapsToBoolean() {
        Schema schema = Schema.of(field("flag", StandardSQLTypeName.BOOL));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals(Type.BOOLEAN, cols.get(0).getType());
    }

    @Test
    public void testStringMapsToVarchar() {
        Schema schema = Schema.of(field("name", StandardSQLTypeName.STRING));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertTrue(cols.get(0).getType().isStringType());
    }

    @Test
    public void testDateMapsToDate() {
        Schema schema = Schema.of(field("dt", StandardSQLTypeName.DATE));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals(Type.DATE, cols.get(0).getType());
    }

    @Test
    public void testTimestampMapsToDatetime() {
        Schema schema = Schema.of(field("ts", StandardSQLTypeName.TIMESTAMP));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals(Type.DATETIME, cols.get(0).getType());
    }

    @Test
    public void testNumericMapsToDecimal() {
        Schema schema = Schema.of(field("price", StandardSQLTypeName.NUMERIC));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Type t = cols.get(0).getType();
        Assertions.assertTrue(t.isDecimalV3());
        ScalarType st = (ScalarType) t;
        Assertions.assertEquals(38, st.getPrecision());
        Assertions.assertEquals(9, st.getScalarScale());
    }

    @Test
    public void testRepeatedFieldMapsToArray() {
        Schema schema = Schema.of(repeatedField("tags", StandardSQLTypeName.STRING));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertTrue(cols.get(0).getType() instanceof ArrayType);
    }

    @Test
    public void testStructMapsToStruct() {
        Field structField = Field.newBuilder("person",
                        com.google.cloud.bigquery.StandardSQLTypeName.STRUCT,
                        FieldList.of(
                                field("first_name", StandardSQLTypeName.STRING),
                                field("age", StandardSQLTypeName.INT64)
                        ))
                .build();
        Schema schema = Schema.of(structField);
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertTrue(cols.get(0).getType() instanceof StructType);
        StructType st = (StructType) cols.get(0).getType();
        Assertions.assertEquals(2, st.getFields().size());
    }

    @Test
    public void testColumnNamesAreLowercase() {
        Schema schema = Schema.of(field("MyColumn", StandardSQLTypeName.STRING));
        List<Column> cols = BigQuerySchemaUtils.toStarRocksColumns(schema);
        Assertions.assertEquals("mycolumn", cols.get(0).getName());
    }
}
