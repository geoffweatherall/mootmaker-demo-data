package com.mootmaker.demodata;

import module java.base;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.ParseAndValidate;
import graphql.ParseAndValidateResult;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;

/**
 * mootmaker-api's GraphQL schema, loaded from the sibling checkout, for checking what this tool
 * sends against what the API accepts (mootmaker-demo-data#23).
 *
 * <p>The schema is read from {@code ../../mootmaker-api/api/mootmaker.graphql} relative to {@code
 * impl/} - the sibling layout {@code deploy.sh} and {@code verify.sh} already assume - or from the
 * {@code MOOTMAKER_SCHEMA} environment variable, which CI sets because a runner cannot check a
 * second repository out beside the first. A missing schema FAILS rather than skips: a guard that
 * quietly turns itself off is the hole this exists to close.
 */
final class ApiSchema {

  private static final String SIBLING_PATH = "../../mootmaker-api/api/mootmaker.graphql";

  private static final GraphQLSchema SCHEMA = load();
  private static final GraphQL GRAPHQL = GraphQL.newGraphQL(SCHEMA).build();

  private ApiSchema() {}

  private static GraphQLSchema load() {
    final String override = System.getenv("MOOTMAKER_SCHEMA");
    final Path path = Path.of(override != null && !override.isBlank() ? override : SIBLING_PATH);
    if (!Files.isRegularFile(path)) {
      throw new IllegalStateException(
          "mootmaker-api's schema was not found at "
              + path.toAbsolutePath().normalize()
              + ". Check mootmaker-api out as a sibling of this repository, or set"
              + " MOOTMAKER_SCHEMA to the schema file.");
    }
    try {
      return UnExecutableSchemaGenerator.makeUnExecutableSchema(
          new SchemaParser().parse(Files.readString(path)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The schema's objections to an operation's own shape - its syntax, its selection, its declared
   * variable types - with no variables involved. Empty if the document is valid.
   */
  static List<String> problemsWith(final String operation) {
    final ParseAndValidateResult result =
        ParseAndValidate.parseAndValidate(
            SCHEMA, ExecutionInput.newExecutionInput(operation).build());
    return result.getErrors().stream().map(GraphQLError::getMessage).toList();
  }

  /**
   * The schema's objections to an operation and its variables, empty if it would be accepted.
   *
   * <p>Executes rather than only validates, because execution also coerces the variables against
   * their declared input types - so a {@code RoomInput} missing a required field, or a number where
   * a string belongs, is caught as well as a bad selection. There are no resolvers, so every field
   * resolves to null; the errors that produces are about the missing data, not the request, and are
   * left out.
   */
  static List<String> problemsWith(final String operation, final Map<String, Object> variables) {
    final ExecutionResult result =
        GRAPHQL.execute(ExecutionInput.newExecutionInput(operation).variables(variables).build());
    return result.getErrors().stream()
        .filter(
            error ->
                error.getErrorType() == ErrorType.ValidationError
                    || error.getErrorType() == ErrorType.InvalidSyntax)
        .map(GraphQLError::getMessage)
        .toList();
  }
}
