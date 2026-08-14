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

import java.util.Map;

/**
 * Configuration properties for the Cloud Spanner external catalog.
 *
 * <pre>
 * CREATE EXTERNAL CATALOG my_spanner
 * PROPERTIES(
 *   "type"                  = "spanner",
 *   "spanner.project.id"    = "my-gcp-project",
 *   "spanner.instance.id"   = "my-instance",
 *   "spanner.auth.type"     = "application_default"
 * );
 * </pre>
 */
public class SpannerProperties {

    // ── Required ───────────────────────────────────────────────────────────────
    public static final String PROJECT_ID  = "spanner.project.id";
    public static final String INSTANCE_ID = "spanner.instance.id";

    // ── Authentication ─────────────────────────────────────────────────────────
    /** Inline service-account JSON key. */
    public static final String CREDENTIALS_JSON = "spanner.credentials.json";
    /** Path to a service-account JSON key file. */
    public static final String CREDENTIALS_FILE = "spanner.credentials.file";
    /**
     * Auth type. Supported values:
     *   "service_account_json"  — use CREDENTIALS_JSON
     *   "service_account_file"  — use CREDENTIALS_FILE
     *   "application_default"   — use ADC (GKE Workload Identity, env var, etc.)
     */
    public static final String AUTH_TYPE = "spanner.auth.type";
    public static final String AUTH_TYPE_SERVICE_ACCOUNT_JSON = "service_account_json";
    public static final String AUTH_TYPE_SERVICE_ACCOUNT_FILE = "service_account_file";
    public static final String AUTH_TYPE_APPLICATION_DEFAULT  = "application_default";

    // ── Endpoint overrides (optional; for Private Service Connect) ─────────────
    /** Override Spanner gRPC endpoint, e.g. spanner-vzit.p.googleapis.com:443 */
    public static final String ENDPOINT = "spanner.endpoint";

    // ── Read tuning ─────────────────────────────────────────────────────────────
    /** Max partition count per table scan (default: 0 = Spanner decides). */
    public static final String MAX_PARTITIONS = "spanner.max.partitions";
    /** Partition size hint in bytes (default: 0 = Spanner decides). */
    public static final String PARTITION_SIZE_BYTES = "spanner.partition.size.bytes";

    // ── Caching ─────────────────────────────────────────────────────────────────
    public static final String ENABLE_TABLE_CACHE      = "spanner.cache.table.enable";
    public static final String TABLE_CACHE_EXPIRE_TIME = "spanner.cache.table.expire";
    public static final String TABLE_CACHE_SIZE        = "spanner.cache.table.size";

    private static final int DEFAULT_TABLE_CACHE_SIZE        = 1000;
    private static final int DEFAULT_TABLE_CACHE_EXPIRE_SECS = 86400; // 1 day

    private final Map<String, String> properties;

    public SpannerProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public String get(String key) {
        return properties.get(key);
    }

    public int getInt(String key) {
        String v = properties.get(key);
        if (v == null || v.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(v);
    }

    public long getLong(String key) {
        String v = properties.get(key);
        if (v == null || v.isEmpty()) {
            return 0L;
        }
        return Long.parseLong(v);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = properties.get(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v);
    }

    public int getTableCacheSize() {
        return getInt(TABLE_CACHE_SIZE) > 0 ? getInt(TABLE_CACHE_SIZE) : DEFAULT_TABLE_CACHE_SIZE;
    }

    public int getTableCacheExpireSecs() {
        return getInt(TABLE_CACHE_EXPIRE_TIME) > 0 ?
                getInt(TABLE_CACHE_EXPIRE_TIME) : DEFAULT_TABLE_CACHE_EXPIRE_SECS;
    }
}
