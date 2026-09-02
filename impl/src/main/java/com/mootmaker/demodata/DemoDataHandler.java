package com.mootmaker.demodata;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.demodata.DemoData.Concerns;
import com.mootmaker.demodata.DemoData.Summary;
import com.mootmaker.demodata.DemoData.Targets;

import module java.base;

/**
 * Lambda entry point for mootmaker-demo-data. Invoked daily by an EventBridge schedule (see
 * deploy/terraform/schedule.tf) and on demand via {@code aws lambda invoke} - see this project's
 * README, which documents the {@code --cli-read-timeout} a caller needs to match this function's
 * 900-second ceiling.
 *
 * <p>The payload carries only the three concern toggles ({@code {"people": false}}), all
 * defaulting to true, so the schedule's empty payload runs everything. Magnitudes - how many
 * people and rooms, how wide the window - come from environment variables set by Terraform, so no
 * invocation can ask for more data than the deployment allows.
 */
public final class DemoDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final Concerns concerns = Concerns.fromPayload(event);
        final Targets targets = Targets.fromEnvironment();
        System.out.println("Running with " + concerns + " and " + targets);

        final Summary summary = DemoData.run(GraphQlClient.fromSsm(), targets, concerns);

        return Map.of(
                "peopleCreated", summary.peopleCreated(),
                "roomsCreated", summary.roomsCreated(),
                "weekdaysToppedUp", summary.weekdaysToppedUp(),
                "meetingsCreated", summary.meetingsCreated());
    }
}
