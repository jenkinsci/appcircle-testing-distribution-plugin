package io.jenkins.plugins.sample;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import java.io.IOException;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class HelloWorldBuilder extends Builder implements SimpleBuildStep {

  private final String name;

  @DataBoundConstructor
  public HelloWorldBuilder(String name) {
    this.name = name;
  }

  @Override
  public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env, @NonNull Launcher launcher, @NonNull TaskListener listener)
          throws InterruptedException, IOException {

    listener.getLogger().println("Name Input: " + this.name);
  }

  @Symbol("greet")
  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

    public FormValidation doCheckName(@QueryParameter String value)
            throws IOException, ServletException {
      if (value.isEmpty())
        return FormValidation.error(Messages.HelloWorldBuilder_DescriptorImpl_errors_missingName());
      if (value.length() < 4)
        return FormValidation.warning(Messages.HelloWorldBuilder_DescriptorImpl_warnings_tooShort());
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
