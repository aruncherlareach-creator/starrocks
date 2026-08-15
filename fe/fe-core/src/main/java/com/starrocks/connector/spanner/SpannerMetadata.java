// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.BatchReadOnlyTransaction;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.DatabaseInfo;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.SpannerTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.tvr.TvrVersionRange;
import com.starrocks.connector.ConnectorMetadata;
import com.starrocks.connector.ConnectorTableId;
import com.starrocks.connector.GetRemoteFilesParams;
import com.starrocks.connector.RemoteFileDesc;
import com.starrocks.connector.RemoteFileInfo;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.gcp.GCPCloudConfiguration;
import com.starrocks.credential.gcp.GCPCloudCredential;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import com.starrocks.sql.optimizer.statistics.Statistics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SpannerMetadata implements ConnectorMetadata {
    private static final Logger LOG = LogManager.getLogger(SpannerMetadata.class);

    private final Spanner      spanner;
    private final GoogleCredentials credentials;
    private final String       catalogName;
    private final SpannerProperties properties;
    private final String       projectId;
    private final String       instanceId;

    public SpannerMetadata(Spanner spanner, GoogleCredentials credentials,
                            String catalogName, SpannerProperties properties) {
        this.spanner     = spanner;
        this.credentials = credentials;
        this.catalogName = catalogName;
        this.properties  = properties;
        this.projectId   = properties.get(SpannerProperties.PROJECT_ID);
        this.instanceId  = properties.get(SpannerProperties.INSTANCE_ID);
    }

    @Override
    public Table.TableType getTableType() {
        return Table.TableType.SPANNER;
    }

    // ── Database listing ────────────────────────────────────────────────────────

    @Override
    public List<String> listDbNames(ConnectContext context) {
        DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();
        List<String> names = new ArrayList<>();
        for (DatabaseInfo db : adminClient.listDatabases(instanceId).iterateAll()) {
            // DatabaseInfo.getId() returns "projects/p/instances/i/databases/d"
            String[] parts = db.getId().getName().split("/");
            names.add(parts[parts.length - 1]);
        }
        return names;
    }

    @Override
    public Database getDb(ConnectContext context, String name) {
        return new Database(ConnectorTableId.CONNECTOR_ID_GENERATOR.getNextId().asLong(), name);
    }

    // ── Table listing ────────────────────────────────────────────────────────────

    @Override
    public List<String> listTableNames(ConnectContext context, String dbName) {
        DatabaseClient dbClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, dbName));
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = dbClient.singleUse().executeQuery(
                Statement.of("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                             "WHERE TABLE_SCHEMA = '' ORDER BY TABLE_NAME"))) {
            while (rs.next()) {
                tables.add(rs.getString(0));
            }
        }
        return tables;
    }

    // ── Table / schema ─────────────────────────────────────────────────────────

    @Override
    public Table getTable(ConnectContext context, String dbName, String tblName) {
        DatabaseClient dbClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, dbName));

        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = dbClient.singleUse().executeQuery(Statement.newBuilder(
                "SELECT COLUMN_NAME, SPANNER_TYPE, IS_NULLABLE " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @t " +
                "ORDER BY ORDINAL_POSITION")
                .bind("t").to(tblName)
                .build())) {
            while (rs.next()) {
                String colName   = rs.getString(0).toLowerCase();
                String spannerT  = rs.getString(1);
                boolean nullable = "YES".equalsIgnoreCase(rs.getString(2));
                com.starrocks.type.Type srType = parseSpannerDdlType(spannerT);
                columns.add(new Column(colName, srType, nullable));
            }
        }

        if (columns.isEmpty()) {
            LOG.warn("Spanner table {}.{} has no columns or does not exist", dbName, tblName);
            return null;
        }

        return new SpannerTable(catalogName, dbName, tblName, columns,
                System.currentTimeMillis());
    }

    // ── Remote files (partition tokens for parallel scan) ─────────────────────

    @Override
    public List<RemoteFileInfo> getRemoteFiles(Table table, GetRemoteFilesParams params) {
        SpannerTable spannerTable = (SpannerTable) table;
        String dbName  = spannerTable.getCatalogDBName();
        String tblName = spannerTable.getCatalogTableName();

        List<String> fieldNames = params.getFieldNames() != null && !params.getFieldNames().isEmpty()
                ? params.getFieldNames()
                : spannerTable.getColumns().stream()
                        .map(Column::getName).collect(Collectors.toList());

        DatabaseId   databaseId  = DatabaseId.of(projectId, instanceId, dbName);
        BatchClient  batchClient = spanner.getBatchClient(databaseId);

        long maxPartitions       = properties.getLong(SpannerProperties.MAX_PARTITIONS);
        long partitionSizeBytes  = properties.getLong(SpannerProperties.PARTITION_SIZE_BYTES);

        PartitionOptions.Builder poBuilder = PartitionOptions.newBuilder();
        if (maxPartitions > 0) {
            poBuilder.setMaxPartitions(maxPartitions);
        }
        if (partitionSizeBytes > 0) {
            poBuilder.setPartitionSizeBytes(partitionSizeBytes);
        }

        BatchReadOnlyTransaction txn = batchClient.batchReadOnlyTransaction(
                TimestampBound.strong());
        // NOTE: This read-only transaction must remain open while the BE executes all partitions.
        // Spanner read-only transactions expire after ~1 hour. Queries scanning large tables
        // that take longer than 1 hour will fail mid-execution with a FAILED_PRECONDITION error.
        // The access token serialised into spanner_split_infos also expires (typically 1 hour).
        LOG.info("Spanner batch transaction opened for {}/{}", databaseId, tblName);

        // toBytesBase64() is the only public serialization API on BatchTransactionId;
        // getSessionId() and getTransactionId() are package-private.
        String batchTxnBase64 = txn.getBatchTransactionId().toBytesBase64();

        List<Partition> partitions;
        try {
            partitions = txn.partitionRead(
                    poBuilder.build(),
                    tblName,
                    KeySet.all(),
                    fieldNames);
        } catch (SpannerException e) {
            throw new StarRocksConnectorException(
                    "Spanner partitionRead failed for table " + databaseId + "/" + tblName +
                    ": " + e.getMessage(), e);
        }

        if (partitions.isEmpty()) {
            LOG.warn("Spanner partitionRead returned 0 partitions for {}/{} — table may be empty",
                    databaseId, tblName);
            return java.util.Collections.emptyList();
        }

        // Serialize credentials so the BE JNI scanner can authenticate
        String credentialsBase64 = serializeCredentials();

        Map<String, String> commonParams = new HashMap<>();
        commonParams.put("project_id",          projectId);
        commonParams.put("instance_id",         instanceId);
        commonParams.put("database_id",         dbName);
        commonParams.put("table_id",            tblName);
        commonParams.put("required_fields",     String.join(",", fieldNames));
        commonParams.put("credentials_base64",  credentialsBase64);
        commonParams.put("batch_txn_base64",    batchTxnBase64);

        List<RemoteFileDesc> fileDescs = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            String partitionBase64;
            try {
                partitionBase64 = Base64.getEncoder().encodeToString(
                        partitions.get(i).serialize());
            } catch (Exception e) {
                throw new StarRocksConnectorException(
                        "Failed to serialize Spanner partition: " + e.getMessage(), e);
            }
            fileDescs.add(SpannerRemoteFileDesc.create(partitionBase64, i));
        }

        RemoteFileInfo remoteFileInfo = new RemoteFileInfo();
        remoteFileInfo.setFiles(fileDescs);
        remoteFileInfo.setAttachment(commonParams);
        return java.util.Collections.singletonList(remoteFileInfo);
    }

    // ── Statistics ─────────────────────────────────────────────────────────────

    @Override
    public Statistics getTableStatistics(OptimizerContext session,
                                          Table table,
                                          Map<ColumnRefOperator, Column> columns,
                                          List<PartitionKey> partitionKeys,
                                          ScalarOperator predicate,
                                          long limit,
                                          TvrVersionRange tableVersionRange) {
        SpannerTable spannerTable = (SpannerTable) table;
        DatabaseClient dbClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, spannerTable.getCatalogDBName()));

        double rowCount = 10000.0; // default
        try (ResultSet rs = dbClient.singleUse().executeQuery(Statement.newBuilder(
                "SELECT ROW_COUNT_EXACT FROM INFORMATION_SCHEMA.TABLE_STATISTICS " +
                "WHERE TABLE_NAME = @t")
                .bind("t").to(spannerTable.getCatalogTableName())
                .build())) {
            if (rs.next()) {
                rowCount = rs.getLong(0);
            }
        } catch (Exception e) {
            LOG.debug("Could not fetch row count from INFORMATION_SCHEMA.TABLE_STATISTICS: {}", e.getMessage());
        }

        Statistics.Builder builder = Statistics.builder().setOutputRowCount(rowCount);
        final double finalRowCount = rowCount;
        for (Map.Entry<ColumnRefOperator, Column> entry : columns.entrySet()) {
            double ndv = Math.min(finalRowCount, Math.max(1.0, finalRowCount * 0.1));
            builder.addColumnStatistic(entry.getKey(), ColumnStatistic.builder()
                    .setDistinctValuesCount(ndv)
                    .setAverageRowSize(entry.getValue().getType().getTypeSize())
                    .setNullsFraction(0)
                    .setType(ColumnStatistic.StatisticType.ESTIMATE)
                    .build());
        }
        return builder.build();
    }

    // ── CloudConfiguration ─────────────────────────────────────────────────────

    @Override
    public CloudConfiguration getCloudConfiguration() {
        // The actual Spanner auth is handled by the JNI scanner using the access token
        // serialised in spanner_split_infos. Return a minimal GCP cloud configuration
        // so SpannerScanNode.toThrift() can call cloudConfiguration.toThrift().
        GCPCloudCredential gcpCredential = new GCPCloudCredential(
                "", true, "", "", "", "", "", "");
        GCPCloudConfiguration conf = new GCPCloudConfiguration(gcpCredential);
        conf.loadCommonFields(new java.util.HashMap<>(0));
        return conf;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String serializeCredentials() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            LOG.warn("Could not refresh Spanner credentials: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Parse a Spanner DDL type string (e.g. "STRING(MAX)", "INT64", "ARRAY<STRING(256)>")
     * into a StarRocks Type.
     */
    private com.starrocks.type.Type parseSpannerDdlType(String ddlType) {
        if (ddlType == null) {
            return com.starrocks.type.TypeFactory.createDefaultCatalogString();
        }
        String t = ddlType.trim().toUpperCase();

        if (t.startsWith("ARRAY<")) {
            String inner = t.substring(6, t.length() - 1);
            com.starrocks.type.Type elemType = parseSpannerDdlType(inner);
            return new com.starrocks.type.ArrayType(elemType);
        }
        // Strip length/precision: STRING(MAX), STRING(256), BYTES(100), NUMERIC(p,s)
        String base = t.replaceAll("\\(.*\\)", "").trim();
        switch (base) {
            case "BOOL":      return com.starrocks.type.BooleanType.BOOLEAN;
            case "INT64":     return com.starrocks.type.IntegerType.BIGINT;
            case "FLOAT32":   return com.starrocks.type.FloatType.FLOAT;
            case "FLOAT64":   return com.starrocks.type.FloatType.DOUBLE;
            case "NUMERIC":
            case "PG_NUMERIC":
                return com.starrocks.type.TypeFactory.createUnifiedDecimalType(38, 9);
            case "DATE":      return com.starrocks.type.DateType.DATE;
            case "TIMESTAMP": return com.starrocks.type.DateType.DATETIME;
            case "BYTES":     return com.starrocks.type.VarbinaryType.VARBINARY;
            case "JSON":
            case "PG_JSONB":
                return com.starrocks.type.JsonType.JSON;
            case "STRING":
            default:
                return com.starrocks.type.TypeFactory.createDefaultCatalogString();
        }
    }
}
