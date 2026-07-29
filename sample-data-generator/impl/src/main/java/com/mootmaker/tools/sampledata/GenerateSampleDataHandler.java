package com.mootmaker.tools.sampledata;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.tools.sampledata.SampleDataGenerator.Summary;

import module java.base;

/**
 * Lambda entry point for the sample data generator. Invoked on demand via
 * {@code ./run.sh <environment>} (see this project's README) rather than in response to any AWS
 * event, so the input payload is unused - everything the run needs comes from environment
 * variables set by Terraform (see {@link GraphQlClient#fromEnvironment()} and
 * deploy/terraform/lambda.tf).
 *
 * <p>All the actual work - reset, then create people/rooms/meetings - already runs with up to 8
 * concurrent GraphQL calls at a time (see {@link SampleDataGenerator#runInParallel}), which is
 * what keeps a full run safely inside this function's configured timeout despite a Lambda
 * invocation being capped at 15 minutes.
 */
public final class GenerateSampleDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final GraphQlClient client = GraphQlClient.fromEnvironment();
        final Summary summary = SampleDataGenerator.generate(client);
        return Map.of(
                "newPeopleCreated", summary.newPeopleCreated(),
                "existingCognitoLinkedPeople", summary.existingCognitoLinkedPeople(),
                "roomsCreated", summary.roomsCreated(),
                "meetingsCreated", summary.meetingsCreated());
    }
}
