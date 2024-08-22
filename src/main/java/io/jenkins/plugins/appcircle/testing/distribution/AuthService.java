package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

public class AuthService {

    public static UserResponse getAcToken(String pat, @NonNull TaskListener listener)
            throws IOException, URISyntaxException {
        String endpointUrl = "https://auth.appcircle.io/auth/v2/token";
        URI uri = new URI(endpointUrl);

        HttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost(uri);

        // Set headers
        httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded");
        httpPost.setHeader("Accept", "application/json");

        // Set parameters
        Map<String, String> params = new HashMap<>();
        params.put("pat", pat);

        // Convert parameters to form data
        StringEntity entity = new StringEntity(encodeParams(params));
        httpPost.setEntity(entity);

        // Execute the request
        HttpResponse response = httpClient.execute(httpPost);

        // Handle the response
        if (response.getStatusLine().getStatusCode() == 200) {
            String responseBody = EntityUtils.toString(response.getEntity());
            JSONObject responseJson = new JSONObject(responseBody);
            String accessToken = responseJson.getString("access_token");

            return new UserResponse(accessToken);
        } else {
            throw new IOException(
                    "Login Request failed (" + response.getStatusLine().getStatusCode() + " "
                            + response.getStatusLine().getReasonPhrase() + ")" + response);
        }
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
}

class UserResponse {
    private String accessToken;

    public UserResponse(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
}
