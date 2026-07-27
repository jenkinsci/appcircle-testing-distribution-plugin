package io.jenkins.plugins.appcircle.testing.distribution;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class TestingDistributionBuilder extends Builder implements SimpleBuildStep {

    private final Secret personalAPIToken;
    private final String profileName;
    private final Boolean createProfileIfNotExists;
    private final String appPath;
    private final String message;
    private String authEndpoint;
    private String apiEndpoint;

    @DataBoundConstructor
    public TestingDistributionBuilder(
            String personalAPIToken,
            String appPath,
            String profileName,
            Boolean createProfileIfNotExists,
            String message) {
        this.personalAPIToken = Secret.fromString(personalAPIToken);
        this.appPath = appPath;
        this.profileName = profileName;
        this.createProfileIfNotExists = createProfileIfNotExists;
        this.message = message;
    }

    public Secret getPersonalAPIToken() {
        return personalAPIToken;
    }

    public String getAuthEndpoint() {
        return authEndpoint;
    }

    @DataBoundSetter
    public void setAuthEndpoint(String authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    @DataBoundSetter
    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getProfileName() {
        return profileName;
    }

    public boolean isCreateProfileIfNotExists() {
        return createProfileIfNotExists;
    }

    public String getAppPath() {
        return appPath;
    }

    public String getMessage() {
        return message;
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
            if (!validateFileExtension(this.appPath)) {
                throw new IOException("Invalid file extension: " + this.appPath
                        + ". For Android, use .apk or .aab. For iOS, use .ipa");
            }

            UserResponse response = AuthService.getAcToken(this.personalAPIToken, this.authEndpoint, listener);
            listener.getLogger().println("Login is successful.");

            UploadService uploadService = new UploadService(
                    response.getAccessToken(),
                    message,
                    appPath,
                    profileName,
                    this.createProfileIfNotExists,
                    this.apiEndpoint);

            // The artifact lives in the build workspace on the agent, not on the controller,
            // so resolve it through the FilePath passed to perform() (remote-safe access).
            FilePath artifact = workspace.child(this.appPath);
            if (!artifact.exists()) {
                throw new IOException("App path not found in workspace: " + this.appPath);
            }

            Profile profile = uploadService.getProfileId();
            JSONObject uploadResponse = uploadService.uploadArtifact(profile.getId(), artifact, listener);
            if (profile.getCreated()) {
                listener.getLogger()
                        .println("The test profile " + "'" + this.profileName + "'"
                                + " could not be found. A new profile is being created...");
            }
            listener.getLogger().println("App upload process - task id: " + uploadResponse.optString("taskId"));
            uploadService.checkUploadStatus(uploadResponse.optString("taskId"), listener);

        } catch (URISyntaxException e) {
            listener.error("Invalid URI: " + e.getMessage());
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
            run.setResult(Result.FAILURE);
        }
    }

    Boolean validateFileExtension(String filePath) {
        String[] validExtensions = {".apk", ".aab", ".ipa"};
        int lastIndex = filePath.lastIndexOf('.');
        String fileExtension = filePath.substring(lastIndex);

        if (!Arrays.asList(validExtensions).contains(fileExtension)) {
            return false;
        }

        return true;
    }

    @Symbol("appcircleTestingDistribution")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        // Validate the artifact file extension only. No sensitive/back-end access, so no permission
        // check is needed here; the lgtm suppression tells the Jenkins security scan this is intentional.
        // Empty/required validation for the other fields is handled declaratively in config.jelly.
        @POST
        public FormValidation doCheckAppPath(@QueryParameter String value) { // lgtm[jenkins/no-permission-check]
            if (value.isEmpty()) return FormValidation.error("App Path cannot be empty");
            if (!value.matches(".*\\.(apk|aab|ipa)$")) {
                return FormValidation.error("Invalid file extension: For Android, use .apk or .aab. For iOS, use .ipa");
            }
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
