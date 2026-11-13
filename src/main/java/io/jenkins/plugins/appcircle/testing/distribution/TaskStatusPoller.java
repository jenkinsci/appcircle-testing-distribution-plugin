package io.jenkins.plugins.appcircle.testing.distribution;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.json.JSONException;
import org.json.JSONObject;

final class TaskStatusPoller {
    static final long POLL_INTERVAL_MS = 2_000L;
    static final long TIMEOUT_MS = 10 * 60 * 1000L;
    static final long NOT_FOUND_GRACE_MS = 60 * 1000L;
    static final int REQUEST_TIMEOUT_MS = 30_000;

    private static final int TASK_CANCELED = 2;
    private static final int TASK_COMPLETED = 3;

    static final class Reply {
        final int httpStatus;
        final String body;

        Reply(int httpStatus, String body) {
            this.httpStatus = httpStatus;
            this.body = body;
        }
    }

    interface Reader {
        Reply read(String taskId) throws IOException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final int requestTimeoutMs;

    TaskStatusPoller() {
        this(System::currentTimeMillis, Thread::sleep);
    }

    TaskStatusPoller(LongSupplier clock, Sleeper sleeper) {
        this(clock, sleeper, REQUEST_TIMEOUT_MS);
    }

    TaskStatusPoller(LongSupplier clock, Sleeper sleeper, int requestTimeoutMs) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    int requestTimeoutMs() {
        return requestTimeoutMs;
    }

    void waitForCompletion(String taskId, Reader reader, Function<String, String> canceledMessage) throws Exception {
        if (taskId == null || taskId.isEmpty()) {
            throw new Exception("Task ID is not found in the upload response");
        }
        long startedAt = clock.getAsLong();
        String lastState = "not found";
        while (true) {
            Reply reply;
            try {
                reply = reader.read(taskId);
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Status check of upload task " + taskId + " was interrupted.");
                }
                reply = new Reply(0, null);
            }
            long elapsed = clock.getAsLong() - startedAt;
            JSONObject json = parse(reply.body);
            if (reply.httpStatus == 200 && json != null && json.has("stateValue")) {
                int state = json.optInt("stateValue", -1);
                String stateName = json.optString("stateName", String.valueOf(state));
                if (state == TASK_COMPLETED) {
                    return;
                }
                if (state == TASK_CANCELED) {
                    throw new Exception(canceledMessage.apply(stateName));
                }
                lastState = stateName;
            } else if (reply.httpStatus == 200 || reply.httpStatus == 204) {
                if (elapsed >= NOT_FOUND_GRACE_MS) {
                    throw new Exception("Upload task " + taskId
                            + " was not found. Appcircle did not register the task within "
                            + NOT_FOUND_GRACE_MS / 1000 + " seconds.");
                }
            } else if (isTransient(reply.httpStatus)) {
                lastState = reply.httpStatus == 0 ? "no response" : "HTTP " + reply.httpStatus;
            } else {
                throw new Exception(
                        "Could not read the status of upload task " + taskId + ": HTTP " + reply.httpStatus + ".");
            }
            if (elapsed + POLL_INTERVAL_MS > TIMEOUT_MS) {
                throw new Exception("Upload task " + taskId + " did not complete within " + TIMEOUT_MS / 60000
                        + " minutes. Last state: " + lastState + ".");
            }
            sleeper.sleep(POLL_INTERVAL_MS);
        }
    }

    private static boolean isTransient(int httpStatus) {
        return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
    }

    private static JSONObject parse(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }
}
