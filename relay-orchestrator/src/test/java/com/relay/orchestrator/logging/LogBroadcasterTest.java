package com.relay.orchestrator.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogBroadcasterTest {

    @Test
    void detectsTomcatClientAbortMessages() throws Exception {
        LogBroadcaster broadcaster = new LogBroadcaster();
        Method method = LogBroadcaster.class.getDeclaredMethod("isSocketDisconnect", Throwable.class);
        method.setAccessible(true);

        boolean aborted = (boolean) method.invoke(broadcaster,
                new IOException("An established connection was aborted by the software in your host machine"));
        boolean reset = (boolean) method.invoke(broadcaster,
                new IOException("Connection reset by peer"));

        assertTrue(aborted);
        assertTrue(reset);
    }
}
