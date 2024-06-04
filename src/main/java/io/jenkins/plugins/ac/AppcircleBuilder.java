package io.jenkins.plugins.ac;

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
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class AppcircleBuilder extends Builder implements SimpleBuildStep {

    private final String accessToken;
    private final String profileID;
    private final String appPath;
    private final String message;

    @DataBoundConstructor
    public AppcircleBuilder(String accessToken, String appPath, String profileID, String message) {
        this.accessToken = accessToken;
        this.appPath = appPath;
        this.profileID = profileID;
        this.message = message;
    }

    void installNpm(
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            @NonNull FilePath workspace)
            throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("npm");
        args.add("install");
        args.add("-g");
        args.add("@appcircle/cli");

        launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();
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
        args.add(getInputValue(this.accessToken, "Access Token", env));

        int exitCode = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();

        if (exitCode != 0) {
            throw new IOException("Failed to log in to Appcircle. Exit code: " + exitCode);
        }
    }

    void uploadArtifact(
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            @NonNull FilePath workspace)
            throws IOException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        // Add the command itself
        args.add("appcircle");
        args.add("testing-distribution");
        args.add("upload");
        args.add("--app", getInputValue(this.appPath, "App Path", env));
        args.add("--distProfileId", getInputValue(this.profileID, "Profile ID", env));
        args.add("--message", getInputValue(this.message, "Release Message", env));

        int exitCode = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(workspace).join();

        if (exitCode != 0) {
            throw new IOException("Failed to upload build to Appcircle. Exit code: " + exitCode);
        }
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
            listener.getLogger().println("Access Token Input: " + getInputValue(this.accessToken, "Access Token", env));
            listener.getLogger().println("profileID Input: " + getInputValue(this.profileID, "Profile ID", env));
            listener.getLogger().println("appPath Input: " + this.appPath);
            listener.getLogger().println("message Input: " + this.message);
            listener.getLogger().println("AC_PAT: " + env.get("AC_PAT"));

            listener.getLogger().println("Appcircle CLI Installed");
            loginToAC(launcher, env, listener, workspace);
            uploadArtifact(launcher, env, listener, workspace);

        } catch (Exception e) {
            listener.getLogger().println("Failed to run command and parse JSON: " + e.getMessage());
            throw e;
        }
    }

    String getInputValue(@Nullable String inputValue, String inputFieldName, EnvVars envVars) throws IOException, InterruptedException {
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

    @Symbol("greet")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckAccessToken(@QueryParameter String value) throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Access Token cannot be empty");
            return FormValidation.ok();
        }

        public FormValidation doCheckAppPath(@QueryParameter String value) throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("App Path cannot be empty");
            return FormValidation.ok();
        }

        public FormValidation doCheckProfileID(@QueryParameter String value) throws IOException, ServletException {
            if (value.isEmpty()) return FormValidation.error("Profile ID cannot be empty");
            return FormValidation.ok();
        }

        public FormValidation doCheckMessage(@QueryParameter String value) throws IOException, ServletException {
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
            return Messages.HelloWorldBuilder_DescriptorImpl_DisplayName();
        }
    }
}
