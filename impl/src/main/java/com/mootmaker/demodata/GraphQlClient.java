package com.mootmaker.demodata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import module java.base;
import module java.net.http;

/**
 * Minimal HTTP client for executing GraphQL operations against a deployed mootmaker-api
 * environment. There used to be two byte-identical copies of this class, one per tool, kept apart
 * because they were separate Maven projects with no shared-code mechanism; merging the tools into
 * one component deleted the duplicate.
 */
class GraphQlClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI endpoint;
    private final String accessToken;

    GraphQlClient(final String endpoint, final String accessToken) {
        this.endpoint = URI.create(endpoint);
        this.accessToken = accessToken;
    }

    /**
     * Builds a client using the OAuth2 client_credentials flow: this tool's own app client id and
     * secret are exchanged at the Cognito token endpoint for a JWT access token, so no human user
     * or password is involved. Credentials come from SSM Parameter Store at runtime (see
     * {@link SsmSecrets}) rather than from environment variables - it used to borrow the
     * acceptance tests' client, whose secret was passed in as a plaintext Lambda environment
     * variable.
     *
     * <p>The token is fetched <b>once per run</b>, not once per request. Cognito bills M2M token
     * requests with no free tier at all, so a token per GraphQL call would cost hundreds of times
     * more than a token per run on a full seed - see the design's cost appendix.
     */
    static GraphQlClient fromSsm() {
        final SsmSecrets.Credentials credentials = SsmSecrets.load();
        return new GraphQlClient(credentials.graphQlUrl(), fetchAccessToken(credentials));
    }

    private static String fetchAccessToken(final SsmSecrets.Credentials credentials) {
        final String tokenUrl = credentials.tokenUrl();
        final String form = "grant_type=client_credentials"
                + "&client_id=" + urlEncode(credentials.clientId())
                + "&client_secret=" + urlEncode(credentials.clientSecret())
                + "&scope=" + urlEncode(credentials.scope());

        final HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Cognito token endpoint returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            final JsonNode accessToken = OBJECT_MAPPER.readTree(response.body()).get("access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Cognito token response contained no access_token: " + response.body());
            }
            return accessToken.asText();
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to fetch a Cognito access token", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cognito token request was interrupted", e);
        }
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
}
