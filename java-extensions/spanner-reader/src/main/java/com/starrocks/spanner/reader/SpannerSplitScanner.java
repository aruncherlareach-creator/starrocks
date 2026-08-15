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
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.BatchReadOnlyTransaction;
import com.google.cloud.spanner.BatchTransactionId;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;
import com.starrocks.jni.connector.ColumnType;
import com.starrocks.jni.connector.ConnectorScanner;
import com.starrocks.jni.connector.ScannerHelper;
import com.starrocks.utils.loader.ThreadContextClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * BE-side scanner for Cloud Spanner external catalog partitions.
 *
 * <p>Uses the high-level Spanner Java client to execute a single partition returned by
 * {@code BatchClient.partitionRead()} on the FE side. The FE serialises the
 * {@code BatchTransactionId} via {@code toBytesBase64()} and the {@code Partition} via
 * {@code Partition.serialize()}; both are reconstructed here.
 *
 * <p>Params received via the split-info map:
 * <ul>
 *   <li>{@code project_id}         — GCP project</li>
 *   <li>{@code instance_id}        — Spanner instance</li>
 *   <li>{@code database_id}        — Spanner database name</li>
 *   <li>{@code required_fields}    — comma-separated column list</li>
 *   <li>{@code credentials_base64} — OAuth2 access token</li>
 *   <li>{@code batch_txn_base64}   — BatchTransactionId.toBytesBase64()</li>
 *   <li>{@code partition_base64}   — Base64(Partition.serialize())</li>
 * </ul>
 */
public class SpannerSplitScanner extends ConnectorScanner {
    private static final Logger LOG = LogManager.getLogger(SpannerSplitScanner.class);

    private final String projectId;
    private final String instanceId;
    private final String databaseId;
    private final String batchTxnBase64;
    private final String partitionBase64;
    private final String[] requiredFields;
    private final ColumnType[] requiredTypes;
    private final int fetchSize;
    private final ClassLoader classLoader;
    private final GoogleCredentials credentials;

    private Spanner spanner;
    private ResultSet resultSet;

    public SpannerSplitScanner(int fetchSize, Map<String, String> params) {
        this.fetchSize = fetchSize;
        this.projectId = params.get("project_id");
        this.instanceId = params.get("instance_id");
        this.databaseId = params.get("database_id");
        this.batchTxnBase64 = params.get("batch_txn_base64");
        this.partitionBase64 = params.get("partition_base64");
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
            SpannerOptions options = SpannerOptions.newBuilder()
                    .setProjectId(projectId)
                    .setCredentials(credentials)
                    .build();
            spanner = options.getService();

            BatchClient batchClient = spanner.getBatchClient(
                    DatabaseId.of(projectId, instanceId, databaseId));

            BatchTransactionId batchTxnId = BatchTransactionId.fromBytesBase64(batchTxnBase64);
            BatchReadOnlyTransaction batchTxn = batchClient.batchReadOnlyTransaction(batchTxnId);

            byte[] partitionBytes = Base64.getDecoder().decode(partitionBase64);
            Partition partition = Partition.deserialize(partitionBytes);

            resultSet = batchTxn.execute(partition);
            initOffHeapTableWriter(requiredTypes, requiredFields, fetchSize);
        } catch (Exception e) {
            close();
            String msg = "Failed to open Spanner reader for database " + databaseId + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(classLoader)) {
            if (resultSet != null) {
                resultSet.close();
                resultSet = null;
            }
            if (spanner != null) {
                spanner.close();
                spanner = null;
            }
        } catch (Exception e) {
            String msg = "Failed to close Spanner reader for database " + databaseId + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public int getNext() throws IOException {
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(classLoader)) {
            if (resultSet == null) {
                return 0;
            }

            int rowCount = 0;
            while (rowCount < fetchSize && resultSet.next()) {
                Struct row = resultSet.getCurrentRowAsStruct();
                for (int col = 0; col < requiredFields.length; col++) {
                    Value val = row.getValue(requiredFields[col]);
                    if (val.isNull()) {
                        appendData(col, null);
                    } else {
                        Object javaVal = SpannerTypeUtils.getValue(val);
                        appendData(col, javaVal != null ? new SpannerColumnValue(javaVal) : null);
                    }
                }
                rowCount++;
            }
            return rowCount;
        } catch (Exception e) {
            close();
            String msg = "Failed to get next batch from Spanner database " + databaseId + ": ";
            LOG.error("{}{}", msg, e.getMessage(), e);
            throw new IOException(msg + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "SpannerSplitScanner{database='" + databaseId + '\'' +
                ", requiredFields=" + Arrays.toString(requiredFields) +
                ", fetchSize=" + fetchSize + '}';
    }
}
