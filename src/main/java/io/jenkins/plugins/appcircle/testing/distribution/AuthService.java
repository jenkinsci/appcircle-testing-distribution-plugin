package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AuthService {

    public static final String DEFAULT_AUTH_ENDPOINT = "https://auth.appcircle.io";

    public static UserResponse getAcToken(Secret pat, String authEndpoint, @NonNull TaskListener listener)
            throws IOException, URISyntaxException {
        return getAcToken(pat, authEndpoint, null, listener);
    }

    public static UserResponse getAcToken(
            Secret pat, String authEndpoint, String subOrganizationId, @NonNull TaskListener listener)
            throws IOException, URISyntaxException {
        String baseUrl = (authEndpoint == null || authEndpoint.trim().isEmpty())
                ? DEFAULT_AUTH_ENDPOINT
                : authEndpoint.trim().replaceAll("/+$", "");
        String endpointUrl = baseUrl + "/auth/v2/token";
        URI uri = new URI(endpointUrl);

        HttpPost httpPost = new HttpPost(uri);
        httpPost.setConfig(requestConfig());

        // Set headers
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded");
        httpPost.setHeader("Accept", "application/json");

        // Set parameters
        Map<String, String> params = new HashMap<>();
        params.put("pat", Secret.toString(pat));
        if (subOrganizationId != null) {
            params.put("subOrganizationId", URLEncoder.encode(subOrganizationId, StandardCharsets.UTF_8));
        }

        // Convert parameters to form data
        StringEntity entity = new StringEntity(encodeParams(params));
        httpPost.setEntity(entity);

        try (CloseableHttpClient httpClient = AppcircleHttpClients.createDefault();
                CloseableHttpResponse response = httpClient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseBody = EntityUtils.toString(response.getEntity());
                JSONObject responseJson = new JSONObject(responseBody);
                String accessToken = responseJson.getString("access_token");

                return new UserResponse(accessToken);
            }
            EntityUtils.consumeQuietly(response.getEntity());
            throw new TokenRequestException(
                    response.getStatusLine().getStatusCode(),
                    "Login Request failed (" + response.getStatusLine().getStatusCode() + " "
                            + response.getStatusLine().getReasonPhrase() + ")");
        }
    }

    public static void validateEndpoints(String authEndpoint, String apiEndpoint) throws IOException {
        boolean cloudAuth = isCloudEndpoint(authEndpoint, DEFAULT_AUTH_ENDPOINT);
        boolean cloudApi = isCloudEndpoint(apiEndpoint, UploadService.DEFAULT_API_ENDPOINT);
        if (cloudAuth != cloudApi) {
            throw new IOException("Set both authEndpoint and apiEndpoint for a self-hosted Appcircle server,"
                    + " or leave both empty for the Appcircle cloud.");
        }
    }

    private static boolean isCloudEndpoint(String endpoint, String cloudEndpoint) {
        String value = endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
        return value.isEmpty() || value.equalsIgnoreCase(cloudEndpoint);
    }

    public static String getOrganizationId(String accessToken, String apiEndpoint, String name)
            throws IOException, URISyntaxException {
        String baseUrl = (apiEndpoint == null || apiEndpoint.trim().isEmpty())
                ? UploadService.DEFAULT_API_ENDPOINT
                : apiEndpoint.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = new URIBuilder(baseUrl + "/identity/v1/organizations")
                    .addParameter("page", "1")
                    .addParameter("perPage", "1000")
                    .build();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid API endpoint URI: " + e.getMessage(), e);
        }

        HttpGet request = new HttpGet(uri);
        request.setConfig(requestConfig());
        request.setHeader("Authorization", "Bearer " + accessToken);
        request.setHeader("Accept", "application/json");

        try (CloseableHttpClient httpClient = AppcircleHttpClients.createDefault();
                CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity responseEntity = response.getEntity();
            String body = responseEntity == null ? "" : EntityUtils.toString(responseEntity);
            if (status != 200) {
                throw new IOException("Could not list the organizations of this token (HTTP " + status + ").");
            }
            JSONArray organizations;
            try {
                organizations = new JSONObject(body).optJSONArray("data");
            } catch (JSONException e) {
                throw new IOException("Could not list the organizations of this token (unexpected response).", e);
            }
            if (organizations != null) {
                for (int i = 0; i < organizations.length(); i++) {
                    JSONObject organization = organizations.optJSONObject(i);
                    if (organization != null && name.equals(organization.optString("name", null))) {
                        String id = organization.optString("id", "");
                        if (!id.isEmpty()) {
                            return id;
                        }
                    }
                }
            }
        }
        throw new IOException(
                "Sub-organization '" + name + "' could not be found or is not accessible with this token.");
    }

    public static String resolveAccessToken(
            Secret pat,
            String authEndpoint,
            String apiEndpoint,
            String subOrganizationName,
            @NonNull TaskListener listener)
            throws IOException, URISyntaxException {
        validateEndpoints(authEndpoint, apiEndpoint);
        String rootToken = getAcToken(pat, authEndpoint, listener).getAccessToken();
        listener.getLogger().println("Login is successful.");

        String name = subOrganizationName == null ? "" : subOrganizationName.trim();
        if (name.isEmpty()) {
            return rootToken;
        }

        String subOrganizationId = getOrganizationId(rootToken, apiEndpoint, name);
        String subToken;
        try {
            subToken =
                    getAcToken(pat, authEndpoint, subOrganizationId, listener).getAccessToken();
        } catch (TokenRequestException e) {
            throw new IOException(
                    "Could not authenticate against sub-organization '" + name + "' (HTTP " + e.getStatusCode() + ").",
                    e);
        } catch (JSONException e) {
            throw new IOException("Could not obtain an access token for sub-organization '" + name + "'.", e);
        }
        if (subToken == null || subToken.isEmpty()) {
            throw new IOException("Could not obtain an access token for sub-organization '" + name + "'.");
        }
        listener.getLogger().println("Switched to sub-organization: " + name);
        return subToken;
    }

    private static RequestConfig requestConfig() {
        int timeoutMs = TaskStatusPoller.REQUEST_TIMEOUT_MS;
        return RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
    }

    private static String encodeParams(Map<String, String> params) {
        StringBuilder encodedParams = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (encodedParams.length() > 0) {
                encodedParams.append("&");
            }
            encodedParams.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return encodedParams.toString();
    }

    static final class TokenRequestException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        TokenRequestException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int getStatusCode() {
            return statusCode;
        }
    }
}

class UserResponse {
    // Stored as Secret so the access token is never held (or serialized) as plaintext.
    private Secret accessToken;

    public UserResponse(String accessToken) {
        this.accessToken = Secret.fromString(accessToken);
    }

    public String getAccessToken() {
        return Secret.toString(accessToken);
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = Secret.fromString(accessToken);
    }
}
