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

import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a high-level Spanner {@link Value} to a Java object for the off-heap writer.
 */
public final class SpannerTypeUtils {

    private SpannerTypeUtils() {}

    public static Object getValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        switch (value.getType().getCode()) {
            case BOOL:
                return value.getBool();
            case INT64:
                return value.getInt64();
            case FLOAT32:
                return (double) value.getFloat32();
            case FLOAT64:
                return value.getFloat64();
            case NUMERIC:
                return value.getNumeric();
            case STRING:
                return value.getString();
            case BYTES:
                return value.getBytes().toByteArray();
            case JSON:
                return value.getJson();
            case DATE:
                return toLocalDate(value.getDate());
            case TIMESTAMP:
                return toLocalDateTime(value.getTimestamp());
            case ARRAY:
                return extractArray(value);
            default:
                return value.toString();
        }
    }

    static LocalDate toLocalDate(Date d) {
        return LocalDate.of(d.getYear(), d.getMonth(), d.getDayOfMonth());
    }

    static LocalDateTime toLocalDateTime(Timestamp ts) {
        return LocalDateTime.ofEpochSecond(ts.getSeconds(), ts.getNanos(), ZoneOffset.UTC);
    }

    private static List<Object> extractArray(Value value) {
        List<Object> list = new ArrayList<>();
        com.google.cloud.spanner.Type elemType = value.getType().getArrayElementType();
        switch (elemType.getCode()) {
            case BOOL:
                list.addAll(value.getBoolArray());
                break;
            case INT64:
                list.addAll(value.getInt64Array());
                break;
            case FLOAT32:
                for (float f : value.getFloat32Array()) {
                    list.add((double) f);
                }
                break;
            case FLOAT64:
                list.addAll(value.getFloat64Array());
                break;
            case NUMERIC:
                list.addAll(value.getNumericArray());
                break;
            case STRING:
            case JSON:
                list.addAll(value.getStringArray());
                break;
            case DATE:
                for (Date d : value.getDateArray()) {
                    list.add(toLocalDate(d));
                }
                break;
            case TIMESTAMP:
                for (Timestamp ts : value.getTimestampArray()) {
                    list.add(toLocalDateTime(ts));
                }
                break;
            default:
                // Unsupported element type: store as string
                list.add(value.toString());
                break;
        }
        return list;
    }
}
