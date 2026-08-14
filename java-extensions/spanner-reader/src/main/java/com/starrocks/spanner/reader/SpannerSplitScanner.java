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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.spanner.v1.SpannerGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.KeySet;
import com.google.spanner.v1.ReadRequest;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.TransactionSelector;
import com.starrocks.jni.connector.ColumnType;
import com.starrocks.jni.connector.ConnectorScanner;
import com.starrocks.jni.connector.ScannerHelper;
import com.starrocks.utils.loader.ThreadContextClassLoader;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BE-side scanner for Cloud Spanner external catalog partitions.
 *
 * <p>Reads one Spanner partition token using the low-level gRPC {@code Read} RPC so that
 * the full result set is returned in a single call (no streaming chunking complexity).
 * The FE {@code SpannerMetadata.getRemoteFiles()} creates one {@code SpannerRemoteFileDesc}
 * per partition token from {@code BatchClient.partitionRead()}.
 *
 * <p>Params received via the split-info map (set by {@code SpannerScanNode}):
 * <ul>
 *   <li>{@code project_id}        — GCP project</li>
 *   <li>{@code instance_id}       — Spanner instance</li>
 *   <li>{@code database_id}       — Spanner database name</li>
 *   <li>{@code table_id}          — Spanner table name</li>
 *   <li>{@code required_fields}   — comma-separated column list</li>
 *   <li>{@code credentials_base64} — OAuth2 access token</li>
 *   <li>{@code session_name}      — full session resource path</li>
 *   <li>{@code transaction_id}    — Base64 of raw transaction-ID bytes</li>
 *   <li>{@code partition_token}   — Base64 of raw partition-token bytes</li>
 * </ul>
 */
public class SpannerSplitScanner extends ConnectorScanner {
    private static final Logger LOG = LogManager.getLogger(SpannerSplitScanner.class);

    private static final String SPANNER_ENDPOINT = "spanner.googleapis.com";
    private static final int SPANNER_PORT = 443;

    private final String sessionName;
    private final String transactionId;
    private final String partitionToken;
    private final String tableName;
    private final String[] requiredFields;
    private final ColumnType[] requiredTypes;
    private final int fetchSize;
    private final ClassLoader classLoader;
    private final GoogleCredentials credentials;

    private ManagedChannel channel;
    private List<ListValue> rows;
    private int currentRow;

    public SpannerSplitScanner(int fetchSize, Map<String, String> params) {
        this.fetchSize = fetchSize;
        this.sessionName = params.get("session_name");
        this.transactionId = params.get("transaction_id");
        this.partitionToken = params.get("partition_token");
        this.tableName = params.get("table_id");
        this.requiredFields = ScannerHelper.splitAndOmitEmptyStrings(params.get("required_fields"), ",");
        this.classLoader = this.getClass().getClassLoader();
        this.credentials = buildCredentials(params);

        requiredTypes = new ColumnType[requiredFields.length];
        for (int i = 0; i < requiredFields.length; i++) {
            requiredTypes[i] = new ColumnType("varchar");
        }
    }

    private GoogleCredentials buildCredentials(Map<String, String> params) {
        String accessToken = params.getOrDefault("credentials_base64", "");
        if (!accessToken.isEmpty()) {
            return GoogleCredentials.create(new AccessToken(accessToken, null));
        }
        try {
            return GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/spanner.data");
        } catch (IOException e) {
            throw new RuntimeException("No Spanner credentials available: " + e.getMessage(), e);
        }
    }

    @Override
    public void open() throws IOException {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(classLoader)) {
            channel = ManagedChannelBuilder.forAddress(SPANNER_ENDPOINT, SPANNER_PORT)
                    .useTransportSecurity()
                    .build();

            SpannerGrpc.SpannerBlockingStub stub = SpannerGrpc.newBlockingStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(credentials));

            byte[] txnBytes = Base64.getDecoder().decode(transactionId);
            byte[] tokenBytes = Base64.getDecoder().decode(partitionToken);

            ReadRequest readReq = ReadRequest.newBuilder()
                    .setSession(sessionName)
                    .setTransaction(TransactionSelector.newBuilder()
                            .setId(ByteString.copyFrom(txnBytes))
                            .build())
                    .setTable(tableName)
                    .addAllColumns(Arrays.asList(requiredFields))
                    .setKeySet(KeySet.newBuilder().setAll(true).build())
                    .setPartitionToken(ByteString.copyFrom(tokenBytes))
                    .build();

            ResultSet resultSet = stub.read(readReq);
            rows = resultSet.getRowsList();
            currentRow = 0;

            initOffHeapTableWriter(requiredTypes, requiredFields, fetchSize);
        } catch (Exception e) {
            close();
            String msg = "Failed to open Spanner reader for table " + tableName + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(classLoader)) {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                try {
                    if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                        channel.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                channel = null;
            }
        } catch (Exception e) {
            String msg = "Failed to close Spanner reader for table " + tableName + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public int getNext() throws IOException {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(classLoader)) {
            if (rows == null || currentRow >= rows.size()) {
                return 0;
            }

            int rowCount = 0;
            while (rowCount < fetchSize && currentRow < rows.size()) {
                ListValue rowData = rows.get(currentRow++);
                for (int col = 0; col < requiredFields.length; col++) {
                    if (col >= rowData.getValuesCount()) {
                        appendData(col, null);
                        continue;
                    }
                    Value val = rowData.getValues(col);
                    if (val.getKindCase() == Value.KindCase.NULL_VALUE) {
                        appendData(col, null);
                    } else {
                        Object javaVal = SpannerTypeUtils.getValue(val, false);
                        appendData(col, javaVal != null ? new SpannerColumnValue(javaVal) : null);
                    }
                }
                rowCount++;
            }
            return rowCount;
        } catch (Exception e) {
            close();
            String msg = "Failed to get next batch from Spanner table " + tableName + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "SpannerSplitScanner{table='" + tableName + '\'' +
                ", session='" + sessionName + '\'' +
                ", requiredFields=" + Arrays.toString(requiredFields) +
                ", fetchSize=" + fetchSize + '}';
    }
}
