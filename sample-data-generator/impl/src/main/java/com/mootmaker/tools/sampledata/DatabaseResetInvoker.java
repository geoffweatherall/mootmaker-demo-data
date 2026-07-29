package com.mootmaker.tools.sampledata;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Invokes the mootmaker-tools/database-reset Lambda directly (AWS IAM auth, via this function's
 * own execution role - see deploy/terraform/iam.tf), rather than through GraphQL - {@code
 * Mutation.reset} no longer exists (see the mootmaker-api README's "Reset and real user accounts"
 * section). This is the first step of every {@link SampleDataGenerator#generate} run.
 *
 * <p>Reads {@code DATABASE_RESET_FUNCTION_NAME}, set by Terraform to the same deterministic name
 * database-reset's own {@code run.sh} computes (see deploy/terraform/lambda.tf), and picks up its
 * AWS region from the {@code AWS_REGION} variable Lambda sets automatically - this function only
 * ever invokes database-reset in its own region.
 */
final class DatabaseResetInvoker {

    private static final LambdaClient CLIENT = LambdaClient.builder().build();

    private DatabaseResetInvoker() {
    }

    static void invoke() {
        invoke(CLIENT, requireEnv("DATABASE_RESET_FUNCTION_NAME"));
    }

    /** Package-private so DatabaseResetInvokerTest can exercise the response-handling logic against a fake client. */
    static void invoke(final LambdaClient client, final String functionName) {
        final InvokeResponse response = client.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build());

        if (response.functionError() != null) {
            throw new IllegalStateException("database-reset Lambda (" + functionName + ") failed ("
                    + response.functionError() + "): " + response.payload().asUtf8String());
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required. Run this tool via ./run.sh <environment>.");
        }
        return value;
    }
}
