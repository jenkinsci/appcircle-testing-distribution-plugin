package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hudson.model.TaskListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UploadServiceTaskStatusTest {
    private static final Map<String, List<String>> SCRIPTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final CountDownLatch RELEASE = new CountDownLatch(1);
    private static final Map<String, String[]> REQUESTS = new ConcurrentHashMap<>();
    private static ExecutorService executor;
    private static HttpServer server;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/task/v1/tasks/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            String taskId = URLDecoder.decode(rawPath.substring(rawPath.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
            REQUESTS.put(
                    taskId, new String[] {rawPath, exchange.getRequestHeaders().getFirst("Authorization")});
            int count =
                    CALLS.computeIfAbsent(taskId, key -> new AtomicInteger()).incrementAndGet();
            List<String> script = SCRIPTS.getOrDefault(taskId, Collections.singletonList("missing"));
            String step = script.get(Math.min(count, script.size()) - 1);
            if (step.startsWith("redirect:")) {
                exchange.getResponseHeaders().add("Location", step.substring("redirect:".length()));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (step.equals("hang")) {
                try {
                    RELEASE.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            if (step.equals("missing")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            int status = 200;
            String body;
            if (step.startsWith("http")) {
                status = Integer.parseInt(step.substring(4));
                body = "{\"message\":\"error\"}";
            } else {
                int value = Arrays.asList("unknown", "begin", "canceled", "completed")
                        .indexOf(step);
                String name = Character.toUpperCase(step.charAt(0)) + step.substring(1);
                body = "{\"taskId\":\"" + taskId + "\",\"stateValue\":" + value + ",\"stateName\":\"" + name + "\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        RELEASE.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private static String task(String... script) {
        String taskId = "task-" + NEXT_ID.incrementAndGet();
        SCRIPTS.put(taskId, Arrays.asList(script));
        return taskId;
    }

    private static int calls(String taskId) {
        AtomicInteger count = CALLS.get(taskId);
        return count == null ? 0 : count.get();
    }

    private static UploadService service() {
        return new UploadService("token", "message", "app.ipa", "profile", false, baseUrl);
    }

    @Test(timeout = 30_000)
    public void keepsPollingWhileTheStateIsUnknown() throws Exception {
        String taskId = task("unknown", "completed");
        assertTrue(service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals(2, calls(taskId));
    }

    @Test(timeout = 30_000)
    public void waitsForATaskThatAppearsAfterAShort204() throws Exception {
        String taskId = task("missing", "begin", "completed");
        assertTrue(service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals(3, calls(taskId));
    }

    @Test(timeout = 30_000)
    public void retriesTransientHttpErrors() throws Exception {
        String taskId = task("http503", "http429", "completed");
        assertTrue(service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals(3, calls(taskId));
    }

    @Test(timeout = 30_000)
    public void failsFastOnANonTransientHttpError() {
        String taskId = task("http401");
        Exception error = assertThrows(Exception.class, () -> service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals("Could not read the status of upload task " + taskId + ": HTTP 401.", error.getMessage());
        assertEquals(1, calls(taskId));
    }

    @Test(timeout = 30_000)
    public void failsWithTheCanceledMessageOnState2() {
        String taskId = task("canceled");
        Exception error = assertThrows(Exception.class, () -> service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals("App upload status could not processed", error.getMessage());
    }

    @Test(timeout = 30_000)
    public void failsWhenTheTaskIdIsMissing() {
        Exception error = assertThrows(Exception.class, () -> service().checkUploadStatus("", TaskListener.NULL));
        assertEquals("Task ID is not found in the upload response", error.getMessage());
    }

    @Test(timeout = 15_000)
    public void stopsAStatusRequestThatGetsNoAnswer() throws Exception {
        String taskId = task("hang", "completed");
        UploadService service = service();
        service.setTaskStatusPoller(new TaskStatusPoller(System::currentTimeMillis, Thread::sleep, 200));
        assertTrue(service.checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals(2, calls(taskId));
    }

    @Test(timeout = 30_000)
    public void sendsTheTokenAndAnEncodedTaskId() throws Exception {
        String taskId = "a b/c";
        SCRIPTS.put(taskId, Collections.singletonList("completed"));
        assertTrue(service().checkUploadStatus(taskId, TaskListener.NULL));
        assertEquals("/task/v1/tasks/a%20b%2Fc", REQUESTS.get(taskId)[0]);
        assertEquals("Bearer token", REQUESTS.get(taskId)[1]);
    }

    @Test(timeout = 30_000)
    public void doesNotRepeatARequestThatGetsNoAnswer() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            AtomicInteger connections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!raw.isClosed()) {
                        try (Socket socket = raw.accept()) {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                            String line = in.readLine();
                            while (line != null && !line.isEmpty()) {
                                line = in.readLine();
                            }
                            if (connections.incrementAndGet() > 1) {
                                byte[] body = "{\"stateValue\":3,\"stateName\":\"Completed\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                                OutputStream out = socket.getOutputStream();
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                                + body.length + "\r\nConnection: close\r\n\r\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                out.write(body);
                                out.flush();
                            }
                        }
                    }
                } catch (IOException closed) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            List<Long> sleeps = new ArrayList<>();
            UploadService service = new UploadService(
                    "token", "message", "app.ipa", "profile", false, "http://127.0.0.1:" + raw.getLocalPort());
            service.setTaskStatusPoller(new TaskStatusPoller(System::currentTimeMillis, sleeps::add));
            assertTrue(service.checkUploadStatus("task-raw", TaskListener.NULL));
            assertEquals(2, connections.get());
            assertEquals(1, sleeps.size());
        }
    }

    @Test(timeout = 30_000)
    public void doesNotSendTheTokenToAnotherHostOnRedirect() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("not called");
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            seen.set(authorization == null ? "(none)" : authorization);
            byte[] bytes = "{\"stateValue\":3,\"stateName\":\"Completed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        other.start();
        try {
            String taskId =
                    task("redirect:http://127.0.0.1:" + other.getAddress().getPort() + "/task/v1/tasks/x");
            assertTrue(service().checkUploadStatus(taskId, TaskListener.NULL));
            assertEquals("(none)", seen.get());
        } finally {
            other.stop(0);
        }
    }
}
