// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import com.google.spanner.admin.database.v1.StructType;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import com.starrocks.catalog.Column;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.starrocks.type.ArrayType.createArrayType;
import static com.starrocks.type.BooleanType.BOOLEAN;
import static com.starrocks.type.DateType.DATE;
import static com.starrocks.type.DateType.DATETIME;
import static com.starrocks.type.FloatType.DOUBLE;
import static com.starrocks.type.FloatType.FLOAT;
import static com.starrocks.type.IntegerType.BIGINT;
import static com.starrocks.type.TypeFactory.createDefaultCatalogString;
import static com.starrocks.type.TypeFactory.createVarbinaryType;
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
     * Spanner type codes: BOOL, INT64, FLOAT32, FLOAT64, STRING, BYTES, DATE, TIMESTAMP,
     * JSON, ARRAY, STRUCT, NUMERIC, PG_NUMERIC, PG_JSONB, PROTO, ENUM, INTERVAL, UUID.
     */
    public static com.starrocks.type.Type spannerTypeToStarRocks(Type spannerType) {
        TypeCode code = spannerType.getCode();
        switch (code) {
            case BOOL:
                return BOOLEAN;
            case INT64:
                return BIGINT;
            case FLOAT32:
                return FLOAT;
            case FLOAT64:
                return DOUBLE;
            case NUMERIC:
            case PG_NUMERIC:
                // Spanner NUMERIC: 29 digits of integer, 9 of fraction
                return com.starrocks.type.TypeFactory.createUnifiedDecimalType(38, 9);
            case STRING:
            case PG_JSONB:
                return createDefaultCatalogString();
            case BYTES:
                return VARBINARY;
            case DATE:
                return DATE;
            case TIMESTAMP:
                return DATETIME;
            case JSON:
                return com.starrocks.type.TypeDescriptor.createJsonType();
            case ARRAY: {
                com.starrocks.type.Type elementType =
                        spannerTypeToStarRocks(spannerType.getArrayElementType());
                return createArrayType(elementType);
            }
            case STRUCT:
                // Represent STRUCT as JSON — full nested struct mapping is future work
                return com.starrocks.type.TypeDescriptor.createJsonType();
            case PROTO:
            case ENUM:
                // Spanner PROTO/ENUM: represent as VARCHAR (proto bytes/enum name as string)
                return createDefaultCatalogString();
            case INTERVAL:
            case UUID:
                // Spanner INTERVAL/UUID: represent as VARCHAR
                return createDefaultCatalogString();
            default:
                LOG.warn("Unknown Spanner type '{}'; mapping to VARCHAR.", code);
                return createDefaultCatalogString();
        }
    }
}
