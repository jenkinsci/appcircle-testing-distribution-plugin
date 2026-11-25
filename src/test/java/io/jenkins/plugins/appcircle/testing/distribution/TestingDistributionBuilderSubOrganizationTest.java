package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class TestingDistributionBuilderSubOrganizationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static TestingDistributionBuilder builder() {
        return new TestingDistributionBuilder("my-pat", "app.ipa", "Example Profile", false, "Test message");
    }

    @Test
    public void subOrganizationNameSurvivesConfigRoundtrip() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        TestingDistributionBuilder builder = builder();
        builder.setSubOrganizationName("PlugInTest");
        project.getBuildersList().add(builder);

        project = j.configRoundtrip(project);

        TestingDistributionBuilder after = project.getBuildersList().get(TestingDistributionBuilder.class);
        assertEquals("PlugInTest", after.getSubOrganizationName());
    }

    @Test
    public void subOrganizationNameIsOptional() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(builder());

        project = j.configRoundtrip(project);

        TestingDistributionBuilder after = project.getBuildersList().get(TestingDistributionBuilder.class);
        String name = after.getSubOrganizationName();
        assertTrue(name == null || name.isEmpty());
    }
}
