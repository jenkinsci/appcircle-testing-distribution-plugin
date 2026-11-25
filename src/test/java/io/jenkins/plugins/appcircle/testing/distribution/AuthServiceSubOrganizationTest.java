package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AuthServiceSubOrganizationTest {
    private static final String SUB_ORG_ID = "b8610467-5cf8-49c3-81ad-35d7fbd048ec";
    private static final String ORGANIZATIONS = "{\"data\":["
            + "{\"id\":\"root-id\",\"name\":\"Root Org\"},"
            + "{\"id\":\"" + SUB_ORG_ID + "\",\"name\":\"PlugInTest\"},"
            + "{\"id\":\"other-id\",\"name\":\"plugintest\"}]}";

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private volatile int rootTokenStatus = 200;
    private volatile int subTokenStatus = 200;
    private volatile String subTokenBody = "{\"access_token\":\"sub-token\"}";
    private volatile int organizationsStatus = 200;
    private volatile String organizationsBody = ORGANIZATIONS;

    static final class Recorded {
        final String method;
        final String path;
        final String query;
        final String authorization;
        final Map<String, String> form;

        Recorded(String method, String path, String query, String authorization, Map<String, String> form) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.authorization = authorization;
            this.form = form;
        }
    }

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v2/token", exchange -> {
            Map<String, String> form =
                    parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            record(exchange, form);
            if (form.containsKey("subOrganizationId")) {
                respond(exchange, subTokenStatus, subTokenBody);
            } else {
                if (rootTokenStatus != 200) {
                    exchange.getResponseHeaders().add("Set-Cookie", "session=cookie-secret");
                }
                respond(exchange, rootTokenStatus, "{\"access_token\":\"root-token\"}");
            }
        });
        server.createContext("/identity/v1/organizations", exchange -> {
            record(exchange, new HashMap<>());
            respond(exchange, organizationsStatus, organizationsBody);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private void record(HttpExchange exchange, Map<String, String> form) {
        requests.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                form));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        if (body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            int index = pair.indexOf('=');
            form.put(
                    URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private String resolve(String subOrganizationName) throws Exception {
        return AuthService.resolveAccessToken(
                Secret.fromString("my-pat"), baseUrl, baseUrl, subOrganizationName, TaskListener.NULL);
    }

    @Test
    public void tokenRequestWithoutSubOrganizationSendsOnlyPat() throws Exception {
        UserResponse response = AuthService.getAcToken(Secret.fromString("my-pat"), baseUrl, TaskListener.NULL);

        assertEquals("root-token", response.getAccessToken());
        assertEquals(1, requests.size());
        assertEquals(Map.of("pat", "my-pat"), requests.get(0).form);
    }

    @Test
    public void tokenRequestWithSubOrganizationSendsSubOrganizationId() throws Exception {
        UserResponse response =
                AuthService.getAcToken(Secret.fromString("my-pat"), baseUrl, SUB_ORG_ID, TaskListener.NULL);

        assertEquals("sub-token", response.getAccessToken());
        assertEquals(Map.of("pat", "my-pat", "subOrganizationId", SUB_ORG_ID), requests.get(0).form);
    }

    @Test
    public void organizationIdIsResolvedByExactName() throws Exception {
        String id = AuthService.getOrganizationId("root-token", baseUrl, "PlugInTest");

        assertEquals(SUB_ORG_ID, id);
        Recorded request = requests.get(0);
        assertEquals("GET", request.method);
        assertEquals("Bearer root-token", request.authorization);
        assertTrue(request.query.contains("page=1"));
        assertTrue(request.query.contains("perPage=1000"));
    }

    @Test
    public void nameMatchIsCaseSensitive() throws Exception {
        assertEquals("other-id", AuthService.getOrganizationId("root-token", baseUrl, "plugintest"));
        IOException error = assertThrows(
                IOException.class, () -> AuthService.getOrganizationId("root-token", baseUrl, "PLUGINTEST"));
        assertEquals(
                "Sub-organization 'PLUGINTEST' could not be found or is not accessible with this token.",
                error.getMessage());
    }

    @Test
    public void unknownSubOrganizationFailsWithNotFoundMessage() {
        IOException error = assertThrows(IOException.class, () -> resolve("Missing Org"));

        assertEquals(
                "Sub-organization 'Missing Org' could not be found or is not accessible with this token.",
                error.getMessage());
        assertEquals(2, requests.size());
        assertNull(requests.get(0).form.get("subOrganizationId"));
        assertEquals("/identity/v1/organizations", requests.get(1).path);
    }

    @Test
    public void resolveAccessTokenWithoutNameMakesSingleTokenCall() throws Exception {
        assertEquals("root-token", resolve(null));
        assertEquals("root-token", resolve(""));
        assertEquals("root-token", resolve("   "));

        assertEquals(3, requests.size());
        for (Recorded request : requests) {
            assertEquals("/auth/v2/token", request.path);
            assertEquals(Map.of("pat", "my-pat"), request.form);
        }
    }

    @Test
    public void resolveAccessTokenSwitchesToSubOrganizationToken() throws Exception {
        assertEquals("sub-token", resolve("  PlugInTest "));

        assertEquals(3, requests.size());
        assertEquals(Map.of("pat", "my-pat"), requests.get(0).form);
        assertEquals("Bearer root-token", requests.get(1).authorization);
        assertEquals(Map.of("pat", "my-pat", "subOrganizationId", SUB_ORG_ID), requests.get(2).form);
    }

    @Test
    public void subOrganizationTokenFailureDoesNotFallBackToRoot() {
        subTokenStatus = 403;

        IOException error = assertThrows(IOException.class, () -> resolve("PlugInTest"));

        assertEquals("Could not authenticate against sub-organization 'PlugInTest' (HTTP 403).", error.getMessage());
    }

    @Test
    public void subOrganizationTokenWithoutAccessTokenFails() {
        subTokenBody = "{}";

        IOException error = assertThrows(IOException.class, () -> resolve("PlugInTest"));

        assertEquals("Could not obtain an access token for sub-organization 'PlugInTest'.", error.getMessage());
    }

    @Test
    public void organizationsRequestFailureIsReported() {
        organizationsStatus = 500;

        IOException error = assertThrows(IOException.class, () -> resolve("PlugInTest"));

        assertTrue(error.getMessage().contains("500"));
        assertEquals(2, requests.size());
    }

    @Test
    public void organizationsResponseThatIsNotJsonFails() {
        organizationsBody = "<html>sign in</html>";

        IOException error = assertThrows(IOException.class, () -> resolve("PlugInTest"));

        assertEquals("Could not list the organizations of this token (unexpected response).", error.getMessage());
        assertEquals(2, requests.size());
    }

    @Test
    public void invalidApiEndpointFailsWithIOException() {
        IOException error = assertThrows(
                IOException.class,
                () -> AuthService.resolveAccessToken(
                        Secret.fromString("my-pat"), baseUrl, "http://bad host", "PlugInTest", TaskListener.NULL));

        assertTrue(error.getMessage().startsWith("Invalid API endpoint URI: "));
    }

    @Test
    public void emptySubOrganizationAccessTokenFails() {
        subTokenBody = "{\"access_token\":\"\"}";

        IOException error = assertThrows(IOException.class, () -> resolve("PlugInTest"));

        assertEquals("Could not obtain an access token for sub-organization 'PlugInTest'.", error.getMessage());
    }

    @Test
    public void subOrganizationIdIsFormEncoded() throws Exception {
        AuthService.getAcToken(Secret.fromString("my-pat"), baseUrl, "id&x=1 +2", TaskListener.NULL);

        assertEquals("id&x=1 +2", requests.get(0).form.get("subOrganizationId"));
        assertEquals("my-pat", requests.get(0).form.get("pat"));
    }

    @Test
    public void errorMessagesNeverContainThePat() {
        subTokenStatus = 403;
        IOException rejected = assertThrows(IOException.class, () -> resolve("PlugInTest"));
        IOException missing = assertThrows(IOException.class, () -> resolve("Missing Org"));

        assertFalse(rejected.getMessage().contains("my-pat"));
        assertFalse(missing.getMessage().contains("my-pat"));
    }

    @Test
    public void rootLoginFailureMessageHasNoResponseHeaders() {
        rootTokenStatus = 401;

        IOException error = assertThrows(IOException.class, () -> resolve(null));

        assertEquals("Login Request failed (401 Unauthorized)", error.getMessage());
        assertFalse(error.getMessage().contains("cookie-secret"));
    }

    @Test
    public void onlyOneSelfHostedEndpointSendsNoRequest() {
        IOException authOnly = assertThrows(
                IOException.class,
                () -> AuthService.resolveAccessToken(
                        Secret.fromString("my-pat"), baseUrl, null, null, TaskListener.NULL));
        IOException apiOnly = assertThrows(
                IOException.class,
                () -> AuthService.resolveAccessToken(
                        Secret.fromString("my-pat"), null, baseUrl, "PlugInTest", TaskListener.NULL));

        assertEquals(
                "Set both authEndpoint and apiEndpoint for a self-hosted Appcircle server, or leave both empty for the Appcircle cloud.",
                authOnly.getMessage());
        assertEquals(
                "Set both authEndpoint and apiEndpoint for a self-hosted Appcircle server, or leave both empty for the Appcircle cloud.",
                apiOnly.getMessage());
        assertEquals(0, requests.size());
    }
}
