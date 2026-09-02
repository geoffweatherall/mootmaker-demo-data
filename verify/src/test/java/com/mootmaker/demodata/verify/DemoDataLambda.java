package com.mootmaker.demodata.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import module java.base;

/**
 * Invokes the deployed demo-data Lambda, and mootmaker-api's database-reset, directly with AWS
 * credentials rather than through GraphQL. Function names are computed from the environment name
 * the same way each one's own Terraform names it, rather than read from either project's state.
 */
final class DemoDataLambda {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The function's own ceiling is 900 seconds, so every client-side timeout has to clear that or
     * a legitimately long seed gets reported as a failure while the Lambda keeps running and
     * completes regardless. The SDK's defaults (and the AWS CLI's) are far shorter, which is
     * exactly the trap the design calls out.
     */
    private static final Duration LAMBDA_CEILING = Duration.ofSeconds(960);

    private static final String ENVIRONMENT = requireEnv("ENVIRONMENT");

    private DemoDataLambda() {
    }

    private static LambdaClient client() {
        return LambdaClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(LAMBDA_CEILING)
                        .apiCallAttemptTimeout(LAMBDA_CEILING)
                        .build())
                .build();
    }

    /** Runs demo-data with every concern enabled, as the schedule does, and returns its summary. */
    static JsonNode run() {
        return invoke(ENVIRONMENT + "-mootmaker-demo-data", "{}");
    }

    /** Runs demo-data with the given payload, e.g. {@code {"meetings": false}}. */
    static JsonNode run(final String payloadJson) {
        return invoke(ENVIRONMENT + "-mootmaker-demo-data", payloadJson);
    }

    /**
     * Clears the environment, via mootmaker-api's database-reset. Demo-data itself has no reset
     * path by design, so the suite has to reach for the api's - which is the same two-step
     * sequence a human performs by hand.
     */
    static void reset() {
        invoke(ENVIRONMENT + "-mootmaker-database-reset", "{}");
    }

    private static JsonNode invoke(final String functionName, final String payloadJson) {
        try (LambdaClient lambda = client()) {
            final InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                    .functionName(functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromUtf8String(payloadJson))
                    .build());

            final String body = response.payload().asUtf8String();
            if (response.functionError() != null) {
                throw new IllegalStateException(functionName + " failed: " + body);
            }
            try {
                return OBJECT_MAPPER.readTree(body);
            } catch (final IOException e) {
                throw new IllegalStateException("Could not parse " + functionName + " response: " + body, e);
            }
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required. Run these tests via ./verify.sh <environment>.");
        }
        return value;
    }
}
