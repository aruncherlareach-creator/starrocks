// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import java.util.Objects;

/** Cache key: (instanceId, databaseId, tableId). */
public class SpannerTableName {
    private final String instanceId;
    private final String databaseId;
    private final String tableId;

    public SpannerTableName(String instanceId, String databaseId, String tableId) {
        this.instanceId = instanceId;
        this.databaseId = databaseId;
        this.tableId    = tableId;
    }

    public String getInstanceId()  { return instanceId; }
    public String getDatabaseId()  { return databaseId; }
    public String getTableId()     { return tableId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpannerTableName)) return false;
        SpannerTableName that = (SpannerTableName) o;
        return Objects.equals(instanceId, that.instanceId)
                && Objects.equals(databaseId, that.databaseId)
                && Objects.equals(tableId, that.tableId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, databaseId, tableId);
    }

    @Override
    public String toString() {
        return instanceId + "/" + databaseId + "/" + tableId;
    }
}
