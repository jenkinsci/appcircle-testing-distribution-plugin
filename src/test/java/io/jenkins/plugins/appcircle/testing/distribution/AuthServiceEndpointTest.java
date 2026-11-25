package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public class AuthServiceEndpointTest {
    private static final String MESSAGE =
            "Set both authEndpoint and apiEndpoint for a self-hosted Appcircle server, or leave both empty for the Appcircle cloud.";

    @Test
    public void cloudEndpointsPass() throws Exception {
        AuthService.validateEndpoints(null, null);
        AuthService.validateEndpoints("", "  ");
        AuthService.validateEndpoints("https://auth.appcircle.io/", "https://API.appcircle.io");
    }

    @Test
    public void selfHostedEndpointsPass() throws Exception {
        AuthService.validateEndpoints("https://auth.example.test", "https://api.example.test/");
    }

    @Test
    public void onlyAuthEndpointFails() {
        assertEquals(
                MESSAGE,
                assertThrows(IOException.class, () -> AuthService.validateEndpoints("https://auth.example.test", null))
                        .getMessage());
        assertEquals(
                MESSAGE,
                assertThrows(
                                IOException.class,
                                () -> AuthService.validateEndpoints(
                                        "https://auth.example.test", "https://api.appcircle.io"))
                        .getMessage());
    }

    @Test
    public void onlyApiEndpointFails() {
        assertEquals(
                MESSAGE,
                assertThrows(IOException.class, () -> AuthService.validateEndpoints("", "https://api.example.test"))
                        .getMessage());
    }
}
