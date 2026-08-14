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

import com.google.protobuf.Value;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Converts {@link com.google.protobuf.Value} (as returned by the Spanner gRPC Read API)
 * to Java objects suitable for the off-heap table writer.
 *
 * Spanner encoding rules (per the Cloud Spanner API documentation):
 * <ul>
 *   <li>BOOL    → bool_value</li>
 *   <li>FLOAT64 → number_value (double)</li>
 *   <li>INT64   → string_value (decimal string to avoid precision loss)</li>
 *   <li>NUMERIC → string_value</li>
 *   <li>STRING  → string_value</li>
 *   <li>BYTES   → string_value (base64-encoded)</li>
 *   <li>DATE    → string_value ("YYYY-MM-DD")</li>
 *   <li>TIMESTAMP → string_value (RFC 3339 with nanosecond precision)</li>
 *   <li>JSON    → string_value</li>
 *   <li>ARRAY   → list_value (each element encoded per its type)</li>
 *   <li>STRUCT  → list_value (fields encoded in order)</li>
 * </ul>
 */
public final class SpannerTypeUtils {

    private SpannerTypeUtils() {}

    /**
     * Extracts a Java object from a protobuf {@link Value}.
     *
     * @param value the protobuf value
     * @param isBytes true when the logical Spanner type is BYTES (values are base64-encoded strings)
     * @return the Java object, or {@code null} if the value is NULL
     */
    public static Object getValue(Value value, boolean isBytes) {
        if (value == null) {
            return null;
        }
        switch (value.getKindCase()) {
            case NULL_VALUE:
                return null;
            case BOOL_VALUE:
                return value.getBoolValue();
            case NUMBER_VALUE:
                return value.getNumberValue();
            case STRING_VALUE:
                String s = value.getStringValue();
                if (isBytes) {
                    return Base64.getDecoder().decode(s);
                }
                return s;
            case LIST_VALUE: {
                List<Object> list = new ArrayList<>();
                for (Value elem : value.getListValue().getValuesList()) {
                    list.add(getValue(elem, false));
                }
                return list;
            }
            case STRUCT_VALUE:
                // Struct: return as JSON-like string for now
                return value.getStructValue().toString();
            default:
                return null;
        }
    }
}
