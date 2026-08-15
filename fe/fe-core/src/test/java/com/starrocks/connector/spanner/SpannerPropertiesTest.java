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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class SpannerPropertiesTest {

    private SpannerProperties props(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new SpannerProperties(m);
    }

    @Test
    public void testGetString() {
        SpannerProperties p = props(SpannerProperties.PROJECT_ID, "my-project",
                SpannerProperties.INSTANCE_ID, "my-instance");
        Assertions.assertEquals("my-project", p.get(SpannerProperties.PROJECT_ID));
        Assertions.assertEquals("my-instance", p.get(SpannerProperties.INSTANCE_ID));
    }

    @Test
    public void testGetMissingReturnsNull() {
        SpannerProperties p = props();
        Assertions.assertNull(p.get(SpannerProperties.PROJECT_ID));
    }

    @Test
    public void testGetLongDefault() {
        SpannerProperties p = props();
        Assertions.assertEquals(0L, p.getLong(SpannerProperties.MAX_PARTITIONS));
        Assertions.assertEquals(0L, p.getLong(SpannerProperties.PARTITION_SIZE_BYTES));
    }

    @Test
    public void testGetLongCustom() {
        SpannerProperties p = props(SpannerProperties.MAX_PARTITIONS, "128",
                SpannerProperties.PARTITION_SIZE_BYTES, "10000000");
        Assertions.assertEquals(128L, p.getLong(SpannerProperties.MAX_PARTITIONS));
        Assertions.assertEquals(10_000_000L, p.getLong(SpannerProperties.PARTITION_SIZE_BYTES));
    }

    @Test
    public void testGetBooleanDefault() {
        SpannerProperties p = props();
        Assertions.assertFalse(p.getBoolean(SpannerProperties.ENABLE_TABLE_CACHE, false));
        Assertions.assertTrue(p.getBoolean(SpannerProperties.ENABLE_TABLE_CACHE, true));
    }

    @Test
    public void testGetBooleanCustom() {
        SpannerProperties p = props(SpannerProperties.ENABLE_TABLE_CACHE, "true");
        Assertions.assertTrue(p.getBoolean(SpannerProperties.ENABLE_TABLE_CACHE, false));
    }

    @Test
    public void testTableCacheSizeDefault() {
        SpannerProperties p = props();
        Assertions.assertEquals(1000, p.getTableCacheSize());
    }

    @Test
    public void testTableCacheSizeCustom() {
        SpannerProperties p = props(SpannerProperties.TABLE_CACHE_SIZE, "250");
        Assertions.assertEquals(250, p.getTableCacheSize());
    }

    @Test
    public void testTableCacheExpireDefault() {
        SpannerProperties p = props();
        Assertions.assertEquals(86400, p.getTableCacheExpireSecs());
    }

    @Test
    public void testEndpointOverride() {
        SpannerProperties p = props(SpannerProperties.ENDPOINT, "spanner.us-central1.p.googleapis.com:443");
        Assertions.assertEquals("spanner.us-central1.p.googleapis.com:443",
                p.get(SpannerProperties.ENDPOINT));
    }

    @Test
    public void testAuthTypeApplicationDefault() {
        SpannerProperties p = props(SpannerProperties.AUTH_TYPE,
                SpannerProperties.AUTH_TYPE_APPLICATION_DEFAULT);
        Assertions.assertEquals(SpannerProperties.AUTH_TYPE_APPLICATION_DEFAULT,
                p.get(SpannerProperties.AUTH_TYPE));
    }
}
