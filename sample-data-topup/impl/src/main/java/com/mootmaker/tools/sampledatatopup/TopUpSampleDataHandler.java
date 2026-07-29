package com.mootmaker.tools.sampledatatopup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.tools.sampledatatopup.SampleDataTopUp.Summary;

import module java.base;

/**
 * Lambda entry point for the sample-data-topup tool. Invoked weekly by an EventBridge schedule
 * (see deploy/terraform/schedule.tf) and on demand via {@code ./run.sh <environment>} (see this
 * project's README) - either way the input payload is unused, since everything the run needs
 * comes from environment variables set by Terraform (see {@link GraphQlClient#fromEnvironment()}
 * and deploy/terraform/lambda.tf).
 */
public final class TopUpSampleDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final GraphQlClient client = GraphQlClient.fromEnvironment();
        final Summary summary = SampleDataTopUp.run(client);
        return Map.of(
                "weekdaysToppedUp", summary.weekdaysToppedUp(),
                "meetingsCreated", summary.meetingsCreated());
    }
}
