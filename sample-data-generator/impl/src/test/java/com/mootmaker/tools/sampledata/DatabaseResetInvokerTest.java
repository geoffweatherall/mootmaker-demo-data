package com.mootmaker.tools.sampledata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseResetInvokerTest {

    private static final String FUNCTION_NAME = "test-mootmaker-database-reset";

    @Test
    void invokesTheGivenFunctionWithAnEmptyPayload() {
        final FakeLambdaClient client = new FakeLambdaClient();

        DatabaseResetInvoker.invoke(client, FUNCTION_NAME);

        assertEquals(FUNCTION_NAME, client.lastRequest().functionName());
        assertEquals("{}", client.lastRequest().payload().asUtf8String());
    }

    @Test
    void throwsWhenTheLambdaReportsAFunctionError() {
        final FakeLambdaClient client = new FakeLambdaClient();
        client.failNextInvokeWith("Unhandled");

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> DatabaseResetInvoker.invoke(client, FUNCTION_NAME));

        assertEquals("database-reset Lambda (" + FUNCTION_NAME + ") failed (Unhandled): {\"errorMessage\":\"boom\"}",
                thrown.getMessage());
    }
}
