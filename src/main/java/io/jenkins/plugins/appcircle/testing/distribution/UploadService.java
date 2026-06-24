package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.HttpEntity;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

public class UploadService {
    public static final String DEFAULT_API_ENDPOINT = "https://api.appcircle.io";

    private static final int MAX_RETRIES = 5;

    String authToken;
    String message;
    String appPath;
    String profileName;
    Boolean createProfileIfNotExists;
    String baseUrl;

    @DataBoundConstructor
    public UploadService(
            String authToken, String message, String appPath, String profileName, Boolean createProfileIfNotExists) {
        this(authToken, message, appPath, profileName, createProfileIfNotExists, null);
    }

    public UploadService(
            String authToken,
            String message,
            String appPath,
            String profileName,
            Boolean createProfileIfNotExists,
            String apiEndpoint) {
        this.authToken = authToken;
        this.message = message;
        this.appPath = appPath;
        this.profileName = profileName;
        this.createProfileIfNotExists = createProfileIfNotExists;
        this.baseUrl = (apiEndpoint == null || apiEndpoint.trim().isEmpty())
                ? DEFAULT_API_ENDPOINT
                : apiEndpoint.trim().replaceAll("/+$", "");
    }

    public JSONObject uploadArtifact(String distProfileId, @NonNull TaskListener listener) throws IOException {
        File file = new File(this.appPath);
        String fileName = file.getName();
        long fileSize = file.length();

        // 1) Request signed-URL upload information (size-validated).
        JSONObject uploadInfo = getUploadInformation(distProfileId, fileName, fileSize);
        String fileId = uploadInfo.optString("fileId");
        String uploadUrl = uploadInfo.optString("uploadUrl");
        JSONObject configuration = uploadInfo.optJSONObject("configuration");
        String httpMethod = (configuration != null && !configuration.optString("httpMethod").isEmpty())
                ? configuration.optString("httpMethod").toUpperCase()
                : "PUT";

        // 2) Upload the binary to the signed URL.
        listener.getLogger().println("Uploading file to Appcircle...");
        if ("POST".equals(httpMethod)) {
            uploadViaPost(uploadUrl, file, configuration);
        } else {
            uploadViaPut(uploadUrl, file);
        }
        listener.getLogger().println("File upload finished.");

        // 3) Commit the upload to create the new app version.
        return commitFileUpload(distProfileId, fileId, fileName);
    }

    private JSONObject getUploadInformation(String distProfileId, String fileName, long fileSize) throws IOException {
        try {
            URI uri = new URIBuilder(
                            String.format("%s/distribution/v1/profiles/%s/app-versions", this.baseUrl, distProfileId))
                    .addParameter("action", "uploadInformation")
                    .addParameter("fileName", fileName)
                    .addParameter("fileSize", String.valueOf(fileSize))
                    .build();

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpGet request = new HttpGet(uri);
                request.setHeader("Authorization", "Bearer " + this.authToken);
                request.setHeader("Accept", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Failed to retrieve file upload information (" + status + "): " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid upload information URI: " + e.getMessage(), e);
        }
    }

    private void uploadViaPut(String uploadUrl, File file) throws IOException {
        IOException lastError = null;
        long delayMillis = 1000;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPut request = new HttpPut(uploadUrl);
                request.setEntity(new FileEntity(file, ContentType.APPLICATION_OCTET_STREAM));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    EntityUtils.consumeQuietly(response.getEntity());
                    if (status >= 200 && status < 300) {
                        return;
                    }
                    if (status != 503 || attempt >= MAX_RETRIES) {
                        throw new IOException("File upload failed with status code: " + status);
                    }
                    lastError = new IOException("File upload failed with status code: " + status);
                }
            } catch (NoHttpResponseException | SocketException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                lastError = e;
            }

            sleepWithJitter(delayMillis);
            delayMillis *= 2;
        }

        throw lastError != null ? lastError : new IOException("File upload failed.");
    }

    private void uploadViaPost(String uploadUrl, File file, @Nullable JSONObject configuration) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(uploadUrl);

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            JSONObject signParameters =
                    configuration != null ? configuration.optJSONObject("signParameters") : null;
            if (signParameters != null) {
                for (String key : signParameters.keySet()) {
                    builder.addTextBody(key, signParameters.optString(key));
                }
            }
            // The file field MUST be appended last.
            builder.addPart("file", new FileBody(file));
            request.setEntity(builder.build());

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("File upload failed with status code: " + status);
                }
            }
        }
    }

    private JSONObject commitFileUpload(String distProfileId, String fileId, String fileName) throws IOException {
        try {
            URI uri = new URIBuilder(
                            String.format("%s/distribution/v1/profiles/%s/app-versions", this.baseUrl, distProfileId))
                    .addParameter("action", "commitFileUpload")
                    .build();

            JSONObject payload = new JSONObject();
            payload.put("fileId", fileId);
            payload.put("fileName", fileName);
            payload.put("message", this.message);

            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost request = new HttpPost(uri);
                request.setHeader("Authorization", "Bearer " + this.authToken);
                request.setHeader("Accept", "application/json");
                request.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Commit failed with status code: " + status + ": " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid commit URI: " + e.getMessage(), e);
        }
    }

    private void sleepWithJitter(long delayMillis) throws IOException {
        try {
            // Deterministic jitter (Math.random is unavailable); spread retries by file size hash.
            long jitter = Math.abs((this.appPath + delayMillis).hashCode()) % 300;
            Thread.sleep(delayMillis + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload retry interrupted", ie);
        }
    }

    public AppVersions[] getDistributionProfiles() throws IOException {
        String url = String.format("%s/distribution/v2/profiles", this.baseUrl);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet getRequest = new HttpGet(url);
        getRequest.setHeader("Authorization", "Bearer " + this.authToken);
        getRequest.setHeader("Accept", "application/json");

        try (CloseableHttpResponse response = httpClient.execute(getRequest)) {
            String responseBody = EntityUtils.toString(response.getEntity());

            JSONArray profilesArray = new JSONArray(responseBody);

            AppVersions[] appVersions = new AppVersions[profilesArray.length()];
            for (int i = 0; i < profilesArray.length(); i++) {
                JSONObject profileObject = profilesArray.getJSONObject(i);
                String id = profileObject.optString("id");
                String name = profileObject.optString("name");
                appVersions[i] = new AppVersions(id, name);
            }

            return appVersions;
        } catch (IOException e) {
            throw e;
        }
    }

    public JSONObject createDistributionProfile() throws IOException {
        String url = String.format("%s/distribution/v2/profiles", this.baseUrl);

        // Create HTTP client
        CloseableHttpClient httpClient = HttpClients.createDefault();

        // Create HTTP POST request
        HttpPost postRequest = new HttpPost(url);
        postRequest.setHeader("Authorization", "Bearer " + this.authToken);
        postRequest.setHeader("Content-Type", "application/json");
        postRequest.setHeader("Accept", "application/json");

        // Set payload
        JSONObject json = new JSONObject();
        json.put("name", this.profileName);
        StringEntity entity = new StringEntity(json.toString(), ContentType.APPLICATION_JSON);
        postRequest.setEntity(entity);

        // Execute the request
        try (CloseableHttpResponse response = httpClient.execute(postRequest)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            return new JSONObject(responseBody);
        }
    }

    public Profile getProfileId() throws IOException {
        // Fetch distribution profiles
        AppVersions[] profiles = getDistributionProfiles();
        String profileId = null;
        Boolean isProfileCreated = false;

        // Iterate over the profiles to find the matching one
        for (AppVersions profile : profiles) {
            if (profile.getName().equals(this.profileName)) {
                profileId = profile.getId();
                break;
            }
        }

        // Handle case where profile is not found
        if (profileId == null && !this.createProfileIfNotExists) {
            throw new AbortException(String.format(
                    "Error: The test profile '%s' could not be found. The option 'createProfileIfNotExists' is set to false, so no new profile was created. To automatically create a new profile if it doesn't exist, set 'createProfileIfNotExists' to true.",
                    profileName));
        }

        // Create profile if not found and the option is true
        if (profileId == null && this.createProfileIfNotExists) {
            isProfileCreated = true;
            JSONObject newProfile = this.createDistributionProfile();
            if (newProfile == null) {
                throw new AbortException("Error: The new profile could not be created.");
            }
            profileId = newProfile.getString("id");
        }

        return new Profile(profileId, isProfileCreated);
    }

    Boolean checkUploadStatus(String taskId, @NonNull TaskListener listener) throws Exception {
        String url = String.format("%s/task/v1/tasks/%s", this.baseUrl, taskId);
        String result = "";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + this.authToken);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    result = EntityUtils.toString(entity);
                }

                JSONObject jsonResponse = new JSONObject(result);
                @Nullable Integer stateValue = jsonResponse.optInt("stateValue", -1);
                @Nullable String stateName = jsonResponse.optString("stateName");

                if (stateName == null) {
                    throw new Error("Upload Status Could Not Received");
                } else if (stateValue == 2) {
                    throw new Exception("App upload status could not processed");
                } else if (stateValue == 1) {
                    Thread.sleep(2000);
                    return checkUploadStatus(taskId, listener);
                } else if (stateValue == 3) {
                    listener.getLogger()
                            .println(this.appPath + " uploaded to the Appcircle Testing Distribution successfully.");
                }
            }
        } catch (Exception e) {
            throw e;
        }

        return true;
    }
}

class Profile {
    private final String id;
    private final boolean created;

    public Profile(String id, Boolean created) {
        this.id = id;
        this.created = created;
    }

    // Getters for id and name
    public String getId() {
        return id;
    }

    public boolean getCreated() {
        return created;
    }
}
