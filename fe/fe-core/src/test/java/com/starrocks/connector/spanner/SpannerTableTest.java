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

import com.google.common.collect.ImmutableList;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.SpannerTable;
import com.starrocks.catalog.Table;
import com.starrocks.thrift.TTableType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.starrocks.type.BooleanType.BOOLEAN;
import static com.starrocks.type.DateType.DATE;
import static com.starrocks.type.DateType.DATETIME;
import static com.starrocks.type.FloatType.DOUBLE;
import static com.starrocks.type.IntegerType.BIGINT;
import static com.starrocks.type.TypeFactory.createDefaultCatalogString;

public class SpannerTableTest {

    static SpannerTable table;

    @BeforeAll
    public static void setUp() {
        List<Column> columns = ImmutableList.of(
                new Column("id", BIGINT, false),
                new Column("name", createDefaultCatalogString(), true),
                new Column("score", DOUBLE, true),
                new Column("active", BOOLEAN, true),
                new Column("created_at", DATETIME, true),
                new Column("birth_date", DATE, true)
        );
        table = new SpannerTable("spanner_catalog", "mydb", "my_table", columns,
                1_700_000_000_000L);
    }

    @Test
    public void testTableType() {
        Assertions.assertEquals(Table.TableType.SPANNER, table.getType());
    }

    @Test
    public void testCatalogName() {
        Assertions.assertEquals("spanner_catalog", table.getCatalogName());
    }

    @Test
    public void testDbName() {
        Assertions.assertEquals("mydb", table.getCatalogDBName());
    }

    @Test
    public void testTableName() {
        Assertions.assertEquals("my_table", table.getCatalogTableName());
        Assertions.assertEquals("my_table", table.getName());
    }

    @Test
    public void testFullSchema() {
        List<Column> cols = table.getFullSchema();
        Assertions.assertEquals(6, cols.size());
        Assertions.assertEquals("id", cols.get(0).getName());
        Assertions.assertEquals(BIGINT, cols.get(0).getType());
        Assertions.assertEquals("name", cols.get(1).getName());
    }

    @Test
    public void testIsUnPartitioned() {
        Assertions.assertTrue(table.isUnPartitioned());
    }

    @Test
    public void testNoPartitionColumns() {
        Assertions.assertTrue(table.getPartitionColumns().isEmpty());
        Assertions.assertTrue(table.getPartitionColumnNames().isEmpty());
    }

    @Test
    public void testIsSupported() {
        Assertions.assertTrue(table.isSupported());
    }

    @Test
    public void testDataColumnNames() {
        List<String> names = table.getDataColumnNames();
        Assertions.assertEquals(6, names.size());
        Assertions.assertTrue(names.contains("id"));
        Assertions.assertTrue(names.contains("name"));
        Assertions.assertTrue(names.contains("score"));
    }

    @Test
    public void testUUID() {
        String uuid = table.getUUID();
        Assertions.assertNotNull(uuid);
        Assertions.assertTrue(uuid.contains("spanner_catalog"));
        Assertions.assertTrue(uuid.contains("mydb"));
        Assertions.assertTrue(uuid.contains("my_table"));
    }

    @Test
    public void testToThrift() {
        com.starrocks.thrift.TTableDescriptor tdesc = table.toThrift(ImmutableList.of());
        Assertions.assertEquals(TTableType.SPANNER_TABLE, tdesc.getTableType());
        Assertions.assertEquals("my_table", tdesc.getTableName());
        Assertions.assertEquals("mydb", tdesc.getDbName());
        Assertions.assertEquals(6, tdesc.getHdfsTable().getColumns().size());
    }

    @Test
    public void testNullableColumn() {
        Column nameCol = table.getFullSchema().get(1);
        Assertions.assertTrue(nameCol.isAllowNull());
    }

    @Test
    public void testNonNullableColumn() {
        Column idCol = table.getFullSchema().get(0);
        Assertions.assertFalse(idCol.isAllowNull());
    }
}
