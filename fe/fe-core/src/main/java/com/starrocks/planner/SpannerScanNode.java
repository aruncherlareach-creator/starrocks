// Copyright 2021-present StarRocks, Inc. All rights reserved.
// Licensed under the Apache License, Version 2.0
package com.starrocks.planner;

import com.google.common.base.MoreObjects;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.SpannerTable;
import com.starrocks.connector.GetRemoteFilesParams;
import com.starrocks.connector.RemoteFileDesc;
import com.starrocks.connector.RemoteFileInfo;
import com.starrocks.connector.spanner.SpannerRemoteFileDesc;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.thrift.TCloudConfiguration;
import com.starrocks.thrift.TCloudType;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.starrocks.catalog.PartitionKey;

public class SpannerScanNode extends ScanNode {
    private final SpannerTable table;
    private final List<TScanRangeLocations> scanRangeLocationsList = new ArrayList<>();

    public SpannerScanNode(PlanNodeId id, TupleDescriptor desc, String planNodeName) {
        super(id, desc, planNodeName);
        this.table = (SpannerTable) desc.getTable();
    }

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
            return;
        }

        RemoteFileInfo remoteFileInfo = fileInfos.get(0);
        Map<String, String> commonParams = remoteFileInfo.getAttachment() != null
                ? (Map<String, String>) remoteFileInfo.getAttachment()
                : new HashMap<>();

        List<RemoteFileDesc> fileDescs = remoteFileInfo.getFiles();
        if (fileDescs == null || fileDescs.isEmpty()) {
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
        com.starrocks.thrift.THdfsScanNode tHdfsScanNode = new com.starrocks.thrift.THdfsScanNode();
        tHdfsScanNode.setTuple_id(desc.getId().asInt());

        TCloudConfiguration tCloudConfiguration = new TCloudConfiguration();
        tCloudConfiguration.setCloud_type(TCloudType.GCP);
        tHdfsScanNode.setCloud_configuration(tCloudConfiguration);

        msg.hdfs_scan_node = tHdfsScanNode;
        setConnectorCatalogType(msg);
    }
}
