// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import com.starrocks.connector.RemoteFileDesc;

/**
 * Represents one Spanner read partition.
 * The BE uses {@code partitionBase64} (serialized via {@code Partition.serialize()})
 * together with the {@code batch_txn_base64} in commonParams to execute the partition.
 */
public class SpannerRemoteFileDesc extends RemoteFileDesc {

    private final String partitionBase64;
    private final int partitionIndex;

    private SpannerRemoteFileDesc(String partitionBase64, int partitionIndex) {
        super("spanner-partition-" + partitionIndex, "", 1L, 0L, null);
        this.partitionBase64 = partitionBase64;
        this.partitionIndex  = partitionIndex;
    }

    public static SpannerRemoteFileDesc create(String partitionBase64, int partitionIndex) {
        return new SpannerRemoteFileDesc(partitionBase64, partitionIndex);
    }

    public String getPartitionBase64() {
        return partitionBase64;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }
}
