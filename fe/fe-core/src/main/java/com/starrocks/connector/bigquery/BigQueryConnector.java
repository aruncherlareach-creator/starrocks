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

package com.starrocks.connector.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryReadSettings;
import com.starrocks.connector.Connector;
import com.starrocks.connector.ConnectorContext;
import com.starrocks.connector.ConnectorMetadata;
import com.starrocks.connector.exception.StarRocksConnectorException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BigQueryConnector implements Connector {
    private static final Logger LOG = LogManager.getLogger(BigQueryConnector.class);

    private final String catalogName;
    private final BigQueryProperties properties;
    private final BigQuery bigQuery;
    private final BigQueryReadClient readClient;
    private final GoogleCredentials credentials;

    private ConnectorMetadata metadata;

    public BigQueryConnector(ConnectorContext context) {
        this.catalogName = context.getCatalogName();
        this.properties = new BigQueryProperties(context.getProperties());
        this.credentials = buildCredentials();
        this.bigQuery = buildBigQueryClient();
        this.readClient = buildReadClient();
    }

    private static final String SCOPES_BIGQUERY = "https://www.googleapis.com/auth/bigquery";
    private static final String SCOPES_CLOUD = "https://www.googleapis.com/auth/cloud-platform";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GoogleCredentials buildCredentials() {
        String credJson = properties.get(BigQueryProperties.CREDENTIALS_JSON);
        String credFile = properties.get(BigQueryProperties.CREDENTIALS_FILE);
        String authType = properties.get(BigQueryProperties.AUTH_TYPE);

        try {
            if (credJson != null && !credJson.isEmpty()) {
                // Patch WIF external_account credentials: if __catalyst_oidc_url is present,
                // fetch the OIDC token and write it to a temp file so the credential source can read it.
                String resolvedJson = resolveWifCredentials(credJson);
                return GoogleCredentials.fromStream(
                        new ByteArrayInputStream(resolvedJson.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(SCOPES_BIGQUERY, SCOPES_CLOUD);
            }
            if (credFile != null && !credFile.isEmpty()) {
                return GoogleCredentials.fromStream(new FileInputStream(credFile))
                        .createScoped(SCOPES_BIGQUERY, SCOPES_CLOUD);
            }
            // ADC — explicit or implicit default. Works on GCE/GKE node SA, Workload Identity,
            // gcloud credentials, and GOOGLE_APPLICATION_CREDENTIALS env var.
            if (authType == null || authType.isEmpty()
                    || BigQueryProperties.AUTH_TYPE_APPLICATION_DEFAULT.equals(authType)) {
                return GoogleCredentials.getApplicationDefault()
                        .createScoped(SCOPES_BIGQUERY, SCOPES_CLOUD);
            }
            throw new StarRocksConnectorException(
                    "Unsupported bigquery.auth.type: '" + authType + "'. " +
                            "Valid values: service_account_json, service_account_file, application_default");
        } catch (IOException e) {
            throw new StarRocksConnectorException("Failed to load BigQuery credentials: " + e.getMessage(), e);
        }
    }

    /**
     * If the credential JSON is an external_account with a {@code __catalyst_oidc_url} field,
     * fetches the OIDC subject token from that URL, writes it to a temp file, and returns a
     * copy of the JSON with {@code credential_source.file} updated to that temp path.
     * Otherwise returns the JSON unchanged.
     */
    private String resolveWifCredentials(String credJson) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(credJson);
        JsonNode oidcUrlNode = root.get("__catalyst_oidc_url");
        if (oidcUrlNode == null || oidcUrlNode.isNull()) {
            return credJson;
        }
        String oidcUrl = oidcUrlNode.asText();
        LOG.info("Fetching WIF subject token from Catalyst OIDC endpoint");

        // Fetch the OIDC token (client_credentials flow; response is JSON with access_token field).
        String tokenJson = fetchOidcToken(oidcUrl);

        // Write the token JSON to a temp file; the credential source will read it.
        Path tmpFile = Files.createTempFile("bq-wif-token-", ".json");
        tmpFile.toFile().deleteOnExit();
        Files.write(tmpFile, tokenJson.getBytes(StandardCharsets.UTF_8));

        // Patch credential_source.file to point at the temp file.
        com.fasterxml.jackson.databind.node.ObjectNode mutable =
                (com.fasterxml.jackson.databind.node.ObjectNode) OBJECT_MAPPER.readTree(credJson);
        com.fasterxml.jackson.databind.node.ObjectNode credSource =
                (com.fasterxml.jackson.databind.node.ObjectNode) mutable.get("credential_source");
        credSource.put("file", tmpFile.toAbsolutePath().toString());
        return OBJECT_MAPPER.writeValueAsString(mutable);
    }

    private String fetchOidcToken(String oidcUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(oidcUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        // The URL already contains query params (client_id, secret, scope) for client_credentials.
        try (OutputStream os = conn.getOutputStream()) {
            os.write(new byte[0]);
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("OIDC token fetch failed, HTTP " + status + " from " + oidcUrl);
        }
        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private BigQuery buildBigQueryClient() {
        String projectId = properties.get(BigQueryProperties.PROJECT_ID);
        return BigQueryOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials)
                .build()
                .getService();
    }

    private BigQueryReadClient buildReadClient() {
        try {
            return BigQueryReadClient.create(
                    BigQueryReadSettings.newBuilder()
                            .setCredentialsProvider(() -> credentials)
                            .build());
        } catch (IOException e) {
            throw new StarRocksConnectorException("Failed to create BigQuery Storage Read client: " + e.getMessage(), e);
        }
    }

    @Override
    public ConnectorMetadata getMetadata() {
        if (metadata == null) {
            try {
                metadata = new BigQueryMetadata(bigQuery, readClient, credentials, catalogName, properties);
            } catch (StarRocksConnectorException e) {
                LOG.error("Failed to create BigQuery metadata for catalog '{}'", catalogName, e);
                throw e;
            }
        }
        return metadata;
    }

    @Override
    public void shutdown() {
        try {
            if (readClient != null) {
                readClient.close();
            }
        } catch (Exception e) {
            LOG.warn("Error closing BigQuery read client for catalog '{}'", catalogName, e);
        }
    }
}
