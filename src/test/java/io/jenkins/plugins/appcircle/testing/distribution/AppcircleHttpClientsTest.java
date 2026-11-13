package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AppcircleHttpClientsTest {
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static HttpServer first;
    private static HttpServer second;

    @BeforeClass
    public static void startServers() throws IOException {
        first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        first.createContext("/same", exchange -> redirect(exchange, 302, url(first, "/end-same")));
        first.createContext("/post-other", exchange -> redirect(exchange, 303, url(second, "/end-other")));
        first.createContext("/end-same", AppcircleHttpClientsTest::record);
        second.createContext("/end-other", AppcircleHttpClientsTest::record);
        first.start();
        second.start();
    }

    @AfterClass
    public static void stopServers() {
        first.stop(0);
        second.stop(0);
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, int status, String location)
            throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void record(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        SEEN.put(exchange.getRequestURI().getPath(), authorization == null ? "(none)" : authorization);
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @Test(timeout = 30_000)
    public void keepsTheTokenOnARedirectToTheSameHost() throws Exception {
        HttpGet request = new HttpGet(url(first, "/same"));
        request.setHeader("Authorization", "Bearer token");
        try (CloseableHttpClient client = AppcircleHttpClients.createDefault();
                CloseableHttpResponse response = client.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }
        assertEquals("Bearer token", SEEN.get("/end-same"));
    }

    @Test(timeout = 30_000)
    public void dropsTheTokenWhenA303SendsAPostToAnotherHost() throws Exception {
        HttpPost request = new HttpPost(url(first, "/post-other"));
        request.setHeader("Authorization", "Bearer token");
        try (CloseableHttpClient client = AppcircleHttpClients.createDefault();
                CloseableHttpResponse response = client.execute(request)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
        }
        assertEquals("(none)", SEEN.get("/end-other"));
    }

    @Test
    public void keepsTheTokenOnlyOnTheSameHost() {
        assertTrue(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", 443, "https"), new HttpHost("API.example.com", -1, "https")));
        assertTrue(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", 80, "http"), new HttpHost("api.example.com", -1, "http")));
        assertTrue(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", -1, "http"), new HttpHost("api.example.com", -1, "https")));
        assertTrue(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", 8080, "http"), new HttpHost("api.example.com", 8080, "http")));
        assertFalse(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", -1, "https"), new HttpHost("evil.example.com", -1, "https")));
        assertFalse(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", 8080, "http"), new HttpHost("api.example.com", 9090, "http")));
        assertFalse(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", -1, "https"), new HttpHost("api.example.com", -1, "http")));
        assertFalse(AppcircleHttpClients.keepsToken(
                new HttpHost("api.example.com", 8443, "https"), new HttpHost("api.example.com", -1, "https")));
    }
}
