package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.Symbol;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class TestingDistributionBuilder extends Builder implements SimpleBuildStep {

    private final Secret accessToken;
    private final String profileID;
    private final String appPath;
    private final String message;

    @DataBoundConstructor
    public TestingDistributionBuilder(Secret accessToken, String appPath, String profileID, String message) {
        this.accessToken = accessToken;
        this.appPath = appPath;
        this.profileID = profileID;
        this.message = message;
    }

    void loginToAC(
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            @NonNull FilePath workspace)
            throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("appcircle");
        args.add("login");
        args.add("--pat");
        args.add(this.accessToken.getPlainText());

        int exitCode = launcher.launch().cmds(args).envs(env).pwd(workspace).join();

        if (exitCode != 0) {
            throw new IOException("Failed to log in to Appcircle. Exit code: " + exitCode);
        }

        listener.getLogger().println("Login is successful.");
    }

    private String getAppcirclePAT(
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            @NonNull FilePath workspace)
            throws IOException, InterruptedException {

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("appcircle");
        args.add("config");
        args.add("get");
        args.add("AC_ACCESS_TOKEN");
        args.add("-o");
        args.add("json");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int exitCode = launcher.launch()
                .cmds(args)
                .envs(env)
                .stdout(outputStream)
                .pwd(workspace)
                .join();

        if (exitCode != 0) {
            throw new IOException("Failed to get AC_ACCESS_TOKEN. Exit code: " + exitCode);
        }

        String output = outputStream.toString(StandardCharsets.UTF_8.name());

        // Parse the JSON response
        String accessToken = parseAccessTokenFromJson(output);
        if (accessToken == null) {
            throw new IOException("Failed to parse AC_ACCESS_TOKEN from response: " + output);
        }

        return accessToken;
    }

    private String parseAccessTokenFromJson(String jsonResponse) {
        try {
            JSONObject jsonObject = new JSONObject(jsonResponse);
            return jsonObject.optString("AC_ACCESS_TOKEN", null);
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    Boolean checkUploadStatus(
            String taskId,
            String token,
            @NonNull TaskListener listener,
            @NonNull Launcher launcher,
            @NonNull EnvVars env) {
        String url = "https://api.appcircle.io/task/v1/tasks/" + taskId;
        String result = "";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + token);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    result = EntityUtils.toString(entity);
                }

                JSONObject jsonRespnse = new JSONObject(result);
                @Nullable Integer stateValue = jsonRespnse.optInt("stateValue", -1);
                @Nullable String stateName = jsonRespnse.optString("stateName");

                if (stateName == null || stateValue == null) {
                    listener.getLogger().println("Upload Status Could Not Received");
                } else if (stateValue == 2) {
                    listener.getLogger().println("App uploaded but could not processed");
                } else if (stateValue == 1) {
                    Thread.sleep(2000);
                    return checkUploadStatus(taskId, token, listener, launcher, env);
                } else if (stateValue == 3) {
                    listener.getLogger().println("✔ App uploaded successfully.");
                }
            }
        } catch (Exception e) {
            System.err.println("IO Exception occurred while executing request: " + e.getMessage());
            e.printStackTrace();
        }

        return true;
    }

    String uploadArtifact(
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            @NonNull FilePath workspace)
            throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("appcircle");
        args.add("testing-distribution");
        args.add("upload");
        args.add("--app", getInputValue(this.appPath, "App Path", env));
        args.add("--distProfileId", getInputValue(this.profileID, "Profile ID", env));
        args.add("--message", getInputValue(this.message, "Release Message", env));
        args.add("-o");
        args.add("json");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        int exitCode = launcher.launch()
                .cmds(args)
                .envs(env)
                .stdout(outputStream)
                .pwd(workspace)
                .join();

        if (exitCode != 0) {
            throw new IOException("Failed to upload build to Appcircle. Exit code: " + exitCode);
        }

        String output = outputStream.toString(StandardCharsets.UTF_8.name());
        JSONObject jsonObject = new JSONObject(output);
        String taskID = jsonObject.getString("taskId");
        listener.getLogger().println("TASK ID: " + taskID);

        return taskID;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        try {

            loginToAC(launcher, env, listener, workspace);
            String taskID = uploadArtifact(launcher, env, listener, workspace);
            String acToken = getAppcirclePAT(launcher, env, listener, workspace);
            checkUploadStatus(taskID, acToken, listener, launcher, env);

        } catch (Exception e) {
            listener.getLogger().println("Failed to run command and parse JSON: " + e.getMessage());
            throw e;
        }
    }

    String getInputValue(@Nullable String inputValue, String inputFieldName, EnvVars envVars)
            throws IOException, InterruptedException {
        if (inputValue == null) {
            throw new IOException(inputFieldName + " is empty. Please fulfill the input");
        }

        Pattern pattern = Pattern.compile("\\$\\((.*?)\\)");
        Matcher appPathMatcher = pattern.matcher(inputValue);

        if (appPathMatcher.find()) {
            String variableName = inputValue.substring(2, inputValue.length() - 1);
            return envVars.get(variableName);
        }

        return inputValue;
    }

    @Symbol("appcircleTestingDistribution")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @POST
        public FormValidation doCheckAccessToken(@QueryParameter @NonNull String value)
                throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Access Token cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAppPath(@QueryParameter @NonNull String value)
                throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("App Path cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckProfileID(@QueryParameter @NonNull String value)
                throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Profile ID cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckMessage(@QueryParameter @NonNull String value)
                throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Message cannot be empty");
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.TestingDistribution_DescriptorImpl_DisplayName();
        }
    }
}
