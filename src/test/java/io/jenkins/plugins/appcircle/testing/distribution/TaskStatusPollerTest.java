package io.jenkins.plugins.appcircle.testing.distribution;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TaskStatusPollerTest {
    private static final String TASK_ID = "task-1";

    private long time;
    private final List<Long> sleeps = new ArrayList<>();
    private int reads;

    private TaskStatusPoller poller() {
        return new TaskStatusPoller(() -> time, millis -> {
            sleeps.add(millis);
            time += millis;
        });
    }

    private TaskStatusPoller.Reader replies(TaskStatusPoller.Reply... script) {
        return taskId -> {
            TaskStatusPoller.Reply reply = script[Math.min(++reads, script.length) - 1];
            if (reply == null) {
                throw new IOException("connection reset");
            }
            return reply;
        };
    }

    private static TaskStatusPoller.Reply state(int value, String name) {
        return new TaskStatusPoller.Reply(
                200, "{\"taskId\":\"" + TASK_ID + "\",\"stateValue\":" + value + ",\"stateName\":\"" + name + "\"}");
    }

    private static final TaskStatusPoller.Reply BEGIN = state(1, "Begin");
    private static final TaskStatusPoller.Reply COMPLETED = state(3, "Completed");
    private static final TaskStatusPoller.Reply MISSING = new TaskStatusPoller.Reply(204, null);
    private static final TaskStatusPoller.Reply HTML = new TaskStatusPoller.Reply(200, "<html>login</html>");
    private static final TaskStatusPoller.Reply UNAVAILABLE =
            new TaskStatusPoller.Reply(503, "{\"message\":\"error\"}");

    @Test(timeout = 10_000)
    public void pollsEveryTwoSecondsUntilCompleted() throws Exception {
        poller().waitForCompletion(TASK_ID, replies(BEGIN, BEGIN, COMPLETED), name -> "canceled");
        assertEquals(3, reads);
        assertEquals(Arrays.asList(2_000L, 2_000L), sleeps);
    }

    @Test(timeout = 10_000)
    public void failsWhenTheTaskStaysMissingPastTheGracePeriod() {
        Exception error = assertThrows(
                Exception.class, () -> poller().waitForCompletion(TASK_ID, replies(MISSING), name -> "canceled"));
        assertEquals(
                "Upload task task-1 was not found. Appcircle did not register the task within 60 seconds.",
                error.getMessage());
        assertEquals(31, reads);
    }

    @Test(timeout = 10_000)
    public void failsOnTimeoutWhileTheTaskStaysInBegin() {
        Exception error = assertThrows(
                Exception.class, () -> poller().waitForCompletion(TASK_ID, replies(BEGIN), name -> "canceled"));
        assertEquals("Upload task task-1 did not complete within 10 minutes. Last state: Begin.", error.getMessage());
        assertEquals(301, reads);
    }

    @Test(timeout = 10_000)
    public void retriesConnectionErrors() throws Exception {
        poller().waitForCompletion(TASK_ID, replies(null, COMPLETED), name -> "canceled");
        assertEquals(2, reads);
    }

    @Test(timeout = 10_000)
    public void failsOnTimeoutWhileTheErrorsStayTransient() {
        Exception error = assertThrows(
                Exception.class, () -> poller().waitForCompletion(TASK_ID, replies(UNAVAILABLE), name -> "canceled"));
        assertEquals(
                "Upload task task-1 did not complete within 10 minutes. Last state: HTTP 503.", error.getMessage());
    }

    @Test(timeout = 10_000)
    public void failsOnTimeoutWhileNoAnswerComesBack() {
        Exception error = assertThrows(Exception.class, () -> poller().waitForCompletion(
                        TASK_ID, replies(new TaskStatusPoller.Reply[] {null}), name -> "canceled"));
        assertEquals(
                "Upload task task-1 did not complete within 10 minutes. Last state: no response.", error.getMessage());
    }

    @Test(timeout = 10_000)
    public void treatsA200WithoutAReadableBodyLikeAMissingTask() {
        Exception error = assertThrows(
                Exception.class, () -> poller().waitForCompletion(TASK_ID, replies(HTML), name -> "canceled"));
        assertEquals(
                "Upload task task-1 was not found. Appcircle did not register the task within 60 seconds.",
                error.getMessage());
    }

    @Test(timeout = 10_000)
    public void failsWhenTheTaskIdIsNull() {
        Exception error = assertThrows(
                Exception.class, () -> poller().waitForCompletion(null, replies(COMPLETED), name -> "canceled"));
        assertEquals("Task ID is not found in the upload response", error.getMessage());
    }

    @Test(timeout = 10_000)
    public void passesAnInterruptOfTheSleepUp() {
        TaskStatusPoller interrupted = new TaskStatusPoller(() -> time, millis -> {
            throw new InterruptedException("aborted");
        });
        assertThrows(
                InterruptedException.class,
                () -> interrupted.waitForCompletion(TASK_ID, replies(BEGIN), name -> "canceled"));
    }

    @Test(timeout = 10_000)
    public void stopsWhenTheThreadIsInterruptedDuringARead() {
        TaskStatusPoller.Reader abortedRead = taskId -> {
            Thread.currentThread().interrupt();
            throw new IOException("Request aborted");
        };
        try {
            assertThrows(InterruptedException.class, () -> poller().waitForCompletion(
                            TASK_ID, abortedRead, name -> "canceled"));
        } finally {
            Thread.interrupted();
        }
    }
}
