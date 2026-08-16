// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import com.google.spanner.v1.StructType;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import com.starrocks.catalog.Column;
import com.starrocks.type.ArrayType;
import com.starrocks.type.JsonType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.starrocks.type.BooleanType.BOOLEAN;
import static com.starrocks.type.DateType.DATE;
import static com.starrocks.type.DateType.DATETIME;
import static com.starrocks.type.FloatType.DOUBLE;
import static com.starrocks.type.IntegerType.BIGINT;
import static com.starrocks.type.TypeFactory.createDefaultCatalogString;
import static com.starrocks.type.VarbinaryType.VARBINARY;

/**
 * Converts Cloud Spanner column type information (from DDL introspection via
 * InformationSchema or StructType) to StarRocks Column definitions.
 */
public class SpannerSchemaUtils {
    private static final Logger LOG = LogManager.getLogger(SpannerSchemaUtils.class);

    private SpannerSchemaUtils() {}

    /**
     * Convert a list of Spanner StructType fields to StarRocks Columns.
     * Used when schema is derived from a PartitionQuery result set metadata.
     */
    public static List<Column> toStarRocksColumns(List<StructType.Field> fields) {
        List<Column> columns = new ArrayList<>();
        for (StructType.Field field : fields) {
            com.starrocks.type.Type srType = spannerTypeToStarRocks(field.getType());
            columns.add(new Column(field.getName().toLowerCase(), srType, true));
        }
        return columns;
    }

    /**
     * Convert a Spanner Type to a StarRocks Type.
     * Spanner type codes (google-cloud-spanner 6.62.0):
     * BOOL, INT64, FLOAT64, STRING, BYTES, DATE, TIMESTAMP,
     * JSON, ARRAY, STRUCT, NUMERIC, PROTO, ENUM.
     * FLOAT32/INTERVAL/UUID are newer additions not in 6.62.0; fall to default.
     * PG_NUMERIC and PG_JSONB are TypeAnnotationCode variants, not TypeCode.
     */
    public static com.starrocks.type.Type spannerTypeToStarRocks(Type spannerType) {
        TypeCode code = spannerType.getCode();
        switch (code) {
            case BOOL:
                return BOOLEAN;
            case INT64:
                return BIGINT;
            case FLOAT64:
                return DOUBLE;
            case NUMERIC:
                // Spanner NUMERIC: 29 integer + 9 fractional digits
                return com.starrocks.type.TypeFactory.createUnifiedDecimalType(38, 9);
            case STRING:
            case PROTO:
            case ENUM:
                return createDefaultCatalogString();
            case BYTES:
                return VARBINARY;
            case DATE:
                return DATE;
            case TIMESTAMP:
            case INTERVAL:
                return DATETIME;
            case JSON:
            case UUID:
                return JsonType.JSON;
            case ARRAY: {
                com.starrocks.type.Type elementType =
                        spannerTypeToStarRocks(spannerType.getArrayElementType());
                return new ArrayType(elementType);
            }
            case STRUCT:
                return JsonType.JSON;
            default:
                LOG.warn("Unknown Spanner type '{}'; mapping to VARCHAR.", code);
                return createDefaultCatalogString();
        }
    }
}
