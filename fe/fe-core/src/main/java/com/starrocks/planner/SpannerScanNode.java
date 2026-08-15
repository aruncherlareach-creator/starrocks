// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0
package com.starrocks.planner;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.SpannerTable;
import com.starrocks.connector.CatalogConnector;
import com.starrocks.connector.GetRemoteFilesParams;
import com.starrocks.connector.RemoteFileDesc;
import com.starrocks.connector.RemoteFileInfo;
import com.starrocks.connector.spanner.SpannerRemoteFileDesc;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.plan.HDFSScanNodePredicates;
import com.starrocks.thrift.TCloudConfiguration;
import com.starrocks.thrift.TCloudType;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.THdfsScanNode;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocations;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SpannerScanNode extends ScanNode {
    private static final Logger LOG = LogManager.getLogger(SpannerScanNode.class);

    private final SpannerTable table;
    private CloudConfiguration cloudConfiguration;
    private final HDFSScanNodePredicates scanNodePredicates = new HDFSScanNodePredicates();
    private final List<TScanRangeLocations> scanRangeLocationsList = new ArrayList<>();

    public SpannerScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName) {
        super(id, desc, planNodeName);
        this.table = (SpannerTable) desc.getTable();
        setupCloudCredential();
    }

    public HDFSScanNodePredicates getScanNodePredicates() {
        return scanNodePredicates;
    }

    private void setupCloudCredential() {
        String catalog = table.getCatalogName();
        if (catalog == null) {
            return;
        }
        CatalogConnector connector = GlobalStateMgr.getCurrentState().getConnectorMgr().getConnector(catalog);
        Preconditions.checkState(connector != null,
                String.format("connector of catalog %s should not be null", catalog));
        cloudConfiguration = connector.getMetadata().getCloudConfiguration();
        Preconditions.checkState(cloudConfiguration != null,
                String.format("cloudConfiguration of catalog %s should not be null", catalog));
    }

    @SuppressWarnings("unchecked")
    public void setupScanRangeLocations(TupleDescriptor tupleDescriptor,
                                        ScalarOperator predicate,
                                        List<PartitionKey> partitionKeys) {
        List<String> fieldNames = tupleDescriptor.getSlots().stream()
                .map(s -> s.getColumn().getName())
                .collect(Collectors.toList());

        GetRemoteFilesParams params = GetRemoteFilesParams.newBuilder()
                .setPartitionKeys(partitionKeys)
                .setPredicate(predicate)
                .setFieldNames(fieldNames)
                .build();

        List<RemoteFileInfo> fileInfos = GlobalStateMgr.getCurrentState()
                .getMetadataMgr().getRemoteFiles(table, params);

        if (fileInfos == null || fileInfos.isEmpty()) {
            LOG.warn("No Spanner partitions returned for {}.{}", table.getCatalogDBName(),
                    table.getCatalogTableName());
            return;
        }

        RemoteFileInfo remoteFileInfo = fileInfos.get(0);
        Map<String, String> commonParams = remoteFileInfo.getAttachment() != null
                ? (Map<String, String>) remoteFileInfo.getAttachment()
                : new HashMap<>();

        List<RemoteFileDesc> fileDescs = remoteFileInfo.getFiles();
        if (fileDescs == null || fileDescs.isEmpty()) {
            LOG.warn("Spanner partitionRead returned 0 partitions for {}.{}", table.getCatalogDBName(),
                    table.getCatalogTableName());
            return;
        }

        for (RemoteFileDesc desc : fileDescs) {
            SpannerRemoteFileDesc spannerDesc = (SpannerRemoteFileDesc) desc;
            TScanRangeLocations scanRangeLocations = new TScanRangeLocations();
            THdfsScanRange hdfsScanRange = new THdfsScanRange();

            Map<String, String> splitInfo = new HashMap<>(commonParams);
            splitInfo.put("partition_token",  spannerDesc.getPartitionToken());
            splitInfo.put("partition_index",  String.valueOf(spannerDesc.getPartitionIndex()));

            hdfsScanRange.setSpanner_split_infos(splitInfo);
            hdfsScanRange.setUse_spanner_jni_reader(true);
            hdfsScanRange.setFile_length(1);
            hdfsScanRange.setLength(1);

            TScanRange scanRange = new TScanRange();
            scanRange.setHdfs_scan_range(hdfsScanRange);
            scanRangeLocations.setScan_range(scanRange);

            com.starrocks.thrift.TScanRangeLocation location =
                    new com.starrocks.thrift.TScanRangeLocation(
                            new com.starrocks.thrift.TNetworkAddress("-1", -1));
            scanRangeLocations.addToLocations(location);
            scanRangeLocationsList.add(scanRangeLocations);
        }
    }

    @Override
    public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
        return scanRangeLocationsList;
    }

    @Override
    public boolean canUseRuntimeAdaptiveDop() {
        return true;
    }

    @Override
    protected String debugString() {
        return MoreObjects.toStringHelper(this)
                .addValue(super.debugString())
                .addValue("spannerTable=" + table.getName())
                .toString();
    }

    @Override
    protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
        StringBuilder output = new StringBuilder();
        output.append(prefix).append("TABLE: ")
                .append(table.getCatalogDBName()).append(".").append(table.getCatalogTableName())
                .append("\n");
        if (detailLevel == TExplainLevel.VERBOSE) {
            output.append(prefix).append("partitions=").append(scanRangeLocationsList.size()).append("\n");
        }
        return output.toString();
    }

    @Override
    protected void toThrift(TPlanNode msg) {
        msg.node_type = TPlanNodeType.HDFS_SCAN_NODE;
        THdfsScanNode tHdfsScanNode = new THdfsScanNode();
        tHdfsScanNode.setTuple_id(desc.getId().asInt());

        String explainString = getExplainString(conjuncts);
        tHdfsScanNode.setSql_predicates(explainString);

        if (table != null) {
            tHdfsScanNode.setTable_name(table.getCatalogTableName());
        }
        HdfsScanNode.setScanOptimizeOptionToThrift(tHdfsScanNode, this);

        TCloudConfiguration tCloudConfiguration = new TCloudConfiguration();
        cloudConfiguration.toThrift(tCloudConfiguration);
        tCloudConfiguration.setCloud_type(TCloudType.GCP);
        tHdfsScanNode.setCloud_configuration(tCloudConfiguration);

        msg.hdfs_scan_node = tHdfsScanNode;
        setConnectorCatalogType(msg);
    }
}
