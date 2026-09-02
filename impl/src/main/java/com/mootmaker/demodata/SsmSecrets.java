package com.mootmaker.demodata;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import module java.base;

/**
 * Reads this tool's credentials and endpoints from SSM Parameter Store at runtime.
 *
 * <p>The alternative - having mootmaker-api's Terraform outputs hand the client secret to this
 * project's Terraform, which writes it into the Lambda's environment variables - is what this
 * replaces. That put the secret in two places it should not have been (readable by anyone with
 * {@code lambda:GetFunctionConfiguration}, and persisted in this project's own Terraform state)
 * and forced a deploy-time dependency on another repository's state. Reading at runtime means the
 * only thing this project's deploy needs to know is the environment name.
 *
 * <p>The paths are derived from the environment name alone (see
 * mootmaker-api/deploy/terraform/demo-data-credentials.tf, which writes them) - the same
 * deterministic-name loose coupling used for the database-reset function name, rather than a
 * cross-project Terraform state read.
 */
final class SsmSecrets {

    /** Everything this tool needs to authenticate against, and call, a deployed environment. */
    record Credentials(String graphQlUrl, String tokenUrl, String clientId, String clientSecret, String scope) {
    }

    private SsmSecrets() {
    }

    static Credentials load() {
        final String environment = System.getenv("ENVIRONMENT");
        if (environment == null || environment.isBlank()) {
            throw new IllegalStateException("ENVIRONMENT environment variable is required (set by Terraform).");
        }
        final String prefix = "/mootmaker/" + environment + "/demo-data/";
        final List<String> names = List.of(
                prefix + "graphql-url", prefix + "token-url", prefix + "client-id",
                prefix + "client-secret", prefix + "scope");

        try (SsmClient ssm = SsmClient.create()) {
            // One GetParameters call rather than five GetParameter calls: fewer round trips on a
            // cold start, and the whole set either resolves or doesn't. withDecryption applies to
            // client-secret, the only SecureString of the five.
            final GetParametersResponse response = ssm.getParameters(GetParametersRequest.builder()
                    .names(names)
                    .withDecryption(true)
                    .build());

            if (!response.invalidParameters().isEmpty()) {
                throw new IllegalStateException("Missing SSM parameter(s) for environment '" + environment + "': "
                        + response.invalidParameters()
                        + ". Has mootmaker-api been deployed to this environment? It creates them - see"
                        + " mootmaker-api/deploy/terraform/demo-data-credentials.tf.");
            }

            final Map<String, String> values = response.parameters().stream()
                    .collect(Collectors.toMap(Parameter::name, Parameter::value));
            return new Credentials(
                    values.get(prefix + "graphql-url"),
                    values.get(prefix + "token-url"),
                    values.get(prefix + "client-id"),
                    values.get(prefix + "client-secret"),
                    values.get(prefix + "scope"));
        }
    }
}
