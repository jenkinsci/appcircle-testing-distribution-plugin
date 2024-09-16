package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
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
    private static final String BASE_URL = "https://api.appcircle.io";

    String authToken;
    String message;
    String appPath;
    String profileName;
    Boolean createProfileIfNotExists;

    @DataBoundConstructor
    public UploadService(
            String authToken, String message, String appPath, String profileName, Boolean createProfileIfNotExists) {
        this.authToken = authToken;
        this.message = message;
        this.appPath = appPath;
        this.profileName = profileName;
        this.createProfileIfNotExists = createProfileIfNotExists;
    }

    public JSONObject uploadArtifact(String distProfileId) throws IOException {
        String url = String.format("%s/distribution/v2/profiles/%s/app-versions", BASE_URL, distProfileId);

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost uploadFile = new HttpPost(url);
        uploadFile.setHeader("Authorization", "Bearer " + this.authToken);

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addTextBody("Message", this.message, ContentType.TEXT_PLAIN);
        builder.addPart("File", new FileBody(new File(this.appPath))).build();

        HttpEntity entity = builder.build();
        String contentType = entity.getContentType().getValue();

        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);

        uploadFile.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        uploadFile.setHeader("Message", this.message);

        uploadFile.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            return new JSONObject(responseBody);
        } catch (IOException e) {
            throw e;
        }
    }

    public AppVersions[] getDistributionProfiles() throws IOException {
        String url = String.format("%s/distribution/v2/profiles", BASE_URL);
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
        String url = String.format("%s/distribution/v2/profiles", BASE_URL);

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
        String url = String.format("%s/task/v1/tasks/%s", BASE_URL, taskId);
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
