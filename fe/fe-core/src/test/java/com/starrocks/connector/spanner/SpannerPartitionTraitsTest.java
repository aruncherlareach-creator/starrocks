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
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.SpannerPartitionKey;
import com.starrocks.catalog.SpannerTable;
import com.starrocks.catalog.Table;
import com.starrocks.connector.ConnectorPartitionTraits;
import com.starrocks.connector.partitiontraits.SpannerPartitionTraits;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.starrocks.type.IntegerType.BIGINT;
import static com.starrocks.type.TypeFactory.createDefaultCatalogString;

public class SpannerPartitionTraitsTest {

    static SpannerTable spannerTable;

    @BeforeAll
    public static void setUp() {
        List<Column> cols = ImmutableList.of(
                new Column("id", BIGINT, false),
                new Column("name", createDefaultCatalogString(), true)
        );
        spannerTable = new SpannerTable("cat", "db", "t", cols, System.currentTimeMillis());
    }

    @Test
    public void testIsSupportedForSpanner() {
        Assertions.assertTrue(ConnectorPartitionTraits.isSupported(Table.TableType.SPANNER));
    }

    @Test
    public void testPCTRefreshNotSupported() {
        Assertions.assertFalse(
                ConnectorPartitionTraits.isSupportPCTRefresh(Table.TableType.SPANNER));
    }

    @Test
    public void testTraitsInstanceIsSpanner() {
        ConnectorPartitionTraits traits = ConnectorPartitionTraits.build(Table.TableType.SPANNER);
        Assertions.assertInstanceOf(SpannerPartitionTraits.class, traits);
    }

    @Test
    public void testCreateEmptyKeyReturnsSpannerPartitionKey() {
        SpannerPartitionTraits traits = new SpannerPartitionTraits();
        PartitionKey key = traits.createEmptyKey();
        Assertions.assertNotNull(key);
        Assertions.assertInstanceOf(SpannerPartitionKey.class, key);
    }

    @Test
    public void testSpannerPartitionKeyNullValues() {
        SpannerPartitionKey key = new SpannerPartitionKey();
        List<String> nullValues = key.nullPartitionValueList();
        Assertions.assertNotNull(nullValues);
        Assertions.assertEquals(1, nullValues.size());
        Assertions.assertEquals("NULL", nullValues.get(0));
    }

    @Test
    public void testGetTableReturnsTableAfterBuild() {
        SpannerPartitionTraits traits = new SpannerPartitionTraits();
        // Build via the public API that wires table to traits
        ConnectorPartitionTraits built = ConnectorPartitionTraits.build(spannerTable);
        Assertions.assertNotNull(built.getTable());
        Assertions.assertEquals(Table.TableType.SPANNER, built.getTable().getType());
    }
}
