// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0

package com.starrocks.connector.spanner;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.starrocks.connector.Connector;
import com.starrocks.connector.ConnectorContext;
import com.starrocks.connector.ConnectorMetadata;
import com.starrocks.connector.exception.StarRocksConnectorException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SpannerConnector implements Connector {
    private static final Logger LOG = LogManager.getLogger(SpannerConnector.class);

    private static final String SCOPES_SPANNER = "https://www.googleapis.com/auth/spanner.data";
    private static final String SCOPES_CLOUD   = "https://www.googleapis.com/auth/cloud-platform";

    private final String catalogName;
    private final SpannerProperties properties;
    private final GoogleCredentials credentials;
    private final Spanner spanner;

    private ConnectorMetadata metadata;

    public SpannerConnector(ConnectorContext context) {
        this.catalogName = context.getCatalogName();
        this.properties  = new SpannerProperties(context.getProperties());
        this.credentials = buildCredentials();
        this.spanner     = buildSpannerClient();
    }

    private GoogleCredentials buildCredentials() {
        String credJson = properties.get(SpannerProperties.CREDENTIALS_JSON);
        String credFile = properties.get(SpannerProperties.CREDENTIALS_FILE);
        String authType = properties.get(SpannerProperties.AUTH_TYPE);

        try {
            if (credJson != null && !credJson.isEmpty()) {
                return GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credJson.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(SCOPES_SPANNER, SCOPES_CLOUD);
            }
            if (credFile != null && !credFile.isEmpty()) {
                return GoogleCredentials.fromStream(new FileInputStream(credFile))
                        .createScoped(SCOPES_SPANNER, SCOPES_CLOUD);
            }
            // ADC — GKE Workload Identity, GOOGLE_APPLICATION_CREDENTIALS, etc.
            if (authType == null || authType.isEmpty()
                    || SpannerProperties.AUTH_TYPE_APPLICATION_DEFAULT.equals(authType)) {
                GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
                if (adc.createScopedRequired()) {
                    adc = adc.createScoped(SCOPES_SPANNER, SCOPES_CLOUD);
                }
                return adc;
            }
            throw new StarRocksConnectorException(
                    "Unsupported spanner.auth.type: '" + authType + "'. " +
                    "Valid values: service_account_json, service_account_file, application_default");
        } catch (IOException e) {
            throw new StarRocksConnectorException(
                    "Failed to load Spanner credentials: " + e.getMessage(), e);
        }
    }

    private Spanner buildSpannerClient() {
        String projectId = properties.get(SpannerProperties.PROJECT_ID);
        String endpoint  = properties.get(SpannerProperties.ENDPOINT);

        SpannerOptions.Builder builder = SpannerOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials);

        if (endpoint != null && !endpoint.isEmpty()) {
            builder.setEmulatorHost(endpoint); // works for both emulator and PSC endpoints
        }

        return builder.build().getService();
    }

    @Override
    public ConnectorMetadata getMetadata() {
        if (metadata == null) {
            try {
                metadata = new SpannerMetadata(
                        spanner,
                        credentials,
                        catalogName,
                        properties);
            } catch (StarRocksConnectorException e) {
                LOG.error("Failed to create Spanner metadata for catalog '{}'", catalogName, e);
                throw e;
            }
        }
        return metadata;
    }

    @Override
    public void shutdown() {
        try {
            if (spanner != null) {
                spanner.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing Spanner client for catalog '{}'", catalogName, e);
        }
    }
}
