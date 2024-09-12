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
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class TestingDistributionBuilder extends Builder implements SimpleBuildStep {

    private final Secret personalAPIToken;
    private final String profileName;
    private final Boolean createProfileIfNotExists;
    private final String appPath;
    private final String message;

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

    public String getPersonalAPIToken() {
        return personalAPIToken.getPlainText();
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
                        + ". For Android, use .apk or .aab. For iOS, use .ipa or use zip for both");
            }

            UserResponse response = AuthService.getAcToken(this.personalAPIToken.getPlainText(), listener);
            listener.getLogger().println("Login is successful.");

            UploadService uploadService = new UploadService(
                    response.getAccessToken(), message, appPath, profileName, this.createProfileIfNotExists);

            Profile profile = uploadService.getProfileId();
            JSONObject uploadResponse = uploadService.uploadArtifact(profile.getId());
            listener.getLogger()
                    .println("The test profile" + "'" + this.profileName + "'"
                            + "could not be found. A new profile is being created...");
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
        String[] validExtensions = {".apk", ".aab", ".ipa", ".zip"};
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

        @POST
        public FormValidation doCheckAccessToken(@QueryParameter @NonNull String value)
                throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Access Token cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAppPath(@QueryParameter @NonNull String value) {
            if (value.isEmpty()) return FormValidation.error("App Path cannot be empty");
            if (!value.matches(".*\\.(apk|aab|ipa|zip)$")) {
                return FormValidation.error(
                        "Invalid file extension: For Android, use .apk or .aab. For iOS, use .ipa or use zip for both");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckProfileName(@QueryParameter @NonNull String value) {
            if (value.isEmpty()) return FormValidation.error("Profile Name cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckCreateProfileIfNotExists(@QueryParameter Boolean value) {
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckMessage(@QueryParameter @NonNull String value) {
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
