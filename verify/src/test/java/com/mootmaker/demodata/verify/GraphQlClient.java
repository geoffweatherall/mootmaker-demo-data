package com.mootmaker.demodata.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import module java.base;
import module java.net.http;

/**
 * Reads back what demo-data produced, so the suite can assert against real stored state rather
 * than the Lambda's own summary of what it thinks it did.
 *
 * <p>Authenticates with the acceptance-test app client (via mootmaker-api's authenticate.sh, the
 * same way that project's own suite does) rather than demo-data's client. The suite is a test
 * harness, not the component - it is deliberately a different identity from the thing under test.
 */
final class GraphQlClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI endpoint = URI.create(requireEnv("GRAPHQL_API_URL"));
    private final String accessToken;

    GraphQlClient() {
        this.accessToken = fetchAccessToken();
    }

    private static String fetchAccessToken() {
        final String form = "grant_type=client_credentials"
                + "&client_id=" + urlEncode(requireEnv("COGNITO_TEST_CLIENT_ID"))
                + "&client_secret=" + urlEncode(requireEnv("COGNITO_TEST_CLIENT_SECRET"))
                + "&scope=" + urlEncode(requireEnv("COGNITO_TEST_SCOPE"));

        final HttpRequest request = HttpRequest.newBuilder(URI.create(requireEnv("COGNITO_TOKEN_URL")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Cognito token endpoint returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return OBJECT_MAPPER.readTree(response.body()).get("access_token").asText();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to fetch a Cognito access token", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cognito token request was interrupted", e);
        }
    }

    JsonNode execute(final String query) {
        return execute(query, Map.of());
    }

    JsonNode execute(final String query, final Map<String, Object> variables) {
        try {
            final String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("query", query, "variables", variables));
            final HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (root.has("errors")) {
                throw new IllegalStateException("GraphQL request failed: " + root.get("errors"));
            }
            return root.get("data");
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to execute GraphQL request", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GraphQL request was interrupted", e);
        }
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required. Run these tests via ./verify.sh <environment>.");
        }
        return value;
    }
}
