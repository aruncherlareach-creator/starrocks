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
 * Represents one Spanner read partition — the BE uses the partitionToken
 * to call streamingRead() and fetch its slice of data.
 */
public class SpannerRemoteFileDesc extends RemoteFileDesc {

    private final String sessionName;
    private final String transactionId;
    private final String partitionToken;
    private final int    partitionIndex;

    private SpannerRemoteFileDesc(String sessionName, String transactionId,
                                   String partitionToken, int partitionIndex) {
        super("spanner-partition-" + partitionIndex, "", 1L, 0L, null);
        this.sessionName     = sessionName;
        this.transactionId   = transactionId;
        this.partitionToken  = partitionToken;
        this.partitionIndex  = partitionIndex;
    }

    public static SpannerRemoteFileDesc create(String sessionName, String transactionId,
                                                String partitionToken, int partitionIndex) {
        return new SpannerRemoteFileDesc(sessionName, transactionId, partitionToken, partitionIndex);
    }

    public String getSessionName() {
        return sessionName;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getPartitionToken() {
        return partitionToken;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }
}
