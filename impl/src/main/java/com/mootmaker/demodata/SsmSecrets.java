package com.mootmaker.demodata;

import module java.base;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * Reads this tool's credentials and endpoints from SSM Parameter Store at runtime.
 *
 * <p>The alternative - having mootmaker-api's Terraform outputs hand the client secret to this
 * project's Terraform, which writes it into the Lambda's environment variables - is what this
 * replaces. That put the secret in two places it should not have been (readable by anyone with
 * {@code lambda:GetFunctionConfiguration}, and persisted in this project's own Terraform state) and
 * forced a deploy-time dependency on another repository's state. Reading at runtime means the only
 * thing this project's deploy needs to know is the environment name.
 *
 * <p>The paths are derived from the environment name alone (see
 * mootmaker-api/deploy/terraform/demo-data-credentials.tf, which writes them) - the same
 * deterministic-name loose coupling used for the database-reset function name, rather than a
 * cross-project Terraform state read.
 */
final class SsmSecrets {

  /** Everything this tool needs to authenticate against, and call, a deployed environment. */
  record Credentials(
      String graphQlUrl, String tokenUrl, String clientId, String clientSecret, String scope) {}

  private SsmSecrets() {}

  private static String requireEnvironment() {
    final String environment = System.getenv("ENVIRONMENT");
    if (environment == null || environment.isBlank()) {
      throw new IllegalStateException(
          "ENVIRONMENT environment variable is required (set by Terraform).");
    }
    return environment;
  }

  /**
   * Person ids that must have at least one meeting on every work day in the window, written by
   * mootmaker-api (see its demo-data-credentials.tf). Empty when the parameter does not exist.
   *
   * <p><b>Optional, deliberately.</b> Unlike the five in {@link #load()}, a missing value here is
   * not an error: an environment whose mootmaker-api predates this parameter must keep seeding
   * exactly as before rather than failing. That also makes the guarantee additive - it can be
   * turned off for an environment by emptying the parameter, without a code change.
   */
  static List<String> guaranteedPersonIds() {
    final String name = "/mootmaker/" + requireEnvironment() + "/demo-data/guaranteed-person-ids";
    try (SsmClient ssm = SsmClient.create()) {
      final GetParametersResponse response =
          ssm.getParameters(GetParametersRequest.builder().names(name).build());
      if (response.parameters().isEmpty()) {
        return List.of();
      }
      return splitIds(response.parameters().getFirst().value());
    }
  }

  /**
   * Splits an SSM StringList value. Package-private so the parsing is testable without AWS - blank
   * entries and stray whitespace are the realistic malformations, since the value is produced by a
   * Terraform join() over a list that may be empty.
   */
  static List<String> splitIds(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(",")).map(String::trim).filter(id -> !id.isEmpty()).toList();
  }

  static Credentials load() {
    final String environment = requireEnvironment();
    final String prefix = "/mootmaker/" + environment + "/demo-data/";
    final List<String> names =
        List.of(
            prefix + "graphql-url",
            prefix + "token-url",
            prefix + "client-id",
            prefix + "client-secret",
            prefix + "scope");

    try (SsmClient ssm = SsmClient.create()) {
      // One GetParameters call rather than five GetParameter calls: fewer round trips on a
      // cold start, and the whole set either resolves or doesn't. withDecryption applies to
      // client-secret, the only SecureString of the five.
      final GetParametersResponse response =
          ssm.getParameters(
              GetParametersRequest.builder().names(names).withDecryption(true).build());

      if (!response.invalidParameters().isEmpty()) {
        throw new IllegalStateException(
            "Missing SSM parameter(s) for environment '"
                + environment
                + "': "
                + response.invalidParameters()
                + ". Has mootmaker-api been deployed to this environment? It creates them - see"
                + " mootmaker-api/deploy/terraform/demo-data-credentials.tf.");
      }

      final Map<String, String> values =
          response.parameters().stream()
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
