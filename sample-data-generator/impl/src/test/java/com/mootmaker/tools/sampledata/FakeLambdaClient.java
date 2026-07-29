package com.mootmaker.tools.sampledata;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/** Minimal in-memory test double covering only the single Invoke call DatabaseResetInvoker makes. */
class FakeLambdaClient implements LambdaClient {

    private InvokeRequest lastRequest;
    private String functionError;

    @Override
    public String serviceName() {
        return "lambda";
    }

    @Override
    public void close() {
    }

    void failNextInvokeWith(final String errorType) {
        this.functionError = errorType;
    }

    InvokeRequest lastRequest() {
        return lastRequest;
    }

    @Override
    public InvokeResponse invoke(final InvokeRequest request) {
        this.lastRequest = request;
        final InvokeResponse.Builder response = InvokeResponse.builder()
                .statusCode(200)
                .payload(SdkBytes.fromUtf8String("{}"));
        if (functionError != null) {
            response.functionError(functionError).payload(SdkBytes.fromUtf8String("{\"errorMessage\":\"boom\"}"));
        }
        return response.build();
    }
}
