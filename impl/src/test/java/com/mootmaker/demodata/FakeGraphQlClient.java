package com.mootmaker.demodata;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A {@link GraphQlClient} that answers reads from a fixed in-memory world and records writes, so
 * the top-up arithmetic can be tested without a deployed environment. Only the operations {@link
 * DemoData} actually issues are understood; anything else fails loudly rather than silently
 * returning nothing - which is what caught the reads still pointing at deleted root fields.
 */
final class FakeGraphQlClient extends GraphQlClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final int existingPeople;
  private final List<String> existingRoomNames;
  private final List<String> createdPeopleNames = Collections.synchronizedList(new ArrayList<>());
  private final List<String> createdRoomNames = Collections.synchronizedList(new ArrayList<>());
  private final List<BulkCall> createMeetingsCalls =
      Collections.synchronizedList(new ArrayList<>());
  private final Set<String> datesWithMeetings = new HashSet<>();
  private final Set<String> rejectSubjects = new HashSet<>();
  private boolean rejectEverything;

  /** One createMeetings call: which date it was for, and the subjects it carried. */
  record BulkCall(String date, List<String> subjects) {
    int size() {
      return subjects.size();
    }
  }

  List<BulkCall> createMeetingsCalls() {
    return List.copyOf(createMeetingsCalls);
  }

  /** Dates this environment should report as already having meetings, so a top-up skips them. */
  void withExistingMeetingsOn(final String... dates) {
    datesWithMeetings.addAll(List.of(dates));
  }

  /** Subjects the API should reject, to exercise the partial-failure path. */
  void rejecting(final String... subjects) {
    rejectSubjects.addAll(List.of(subjects));
  }

  /** Rejects every meeting, for asserting on how a rejection is reported rather than which. */
  void rejectingEverything() {
    rejectEverything = true;
  }

  FakeGraphQlClient(final int existingPeople, final List<String> existingRoomNames) {
    super("http://localhost/graphql", "fake-token");
    this.existingPeople = existingPeople;
    this.existingRoomNames = List.copyOf(existingRoomNames);
  }

  List<String> createdPeopleNames() {
    return List.copyOf(createdPeopleNames);
  }

  List<String> createdRoomNames() {
    return List.copyOf(createdRoomNames);
  }

  @Override
  JsonNode execute(final String query) {
    return execute(query, Map.of());
  }

  @Override
  JsonNode execute(final String query, final Map<String, Object> variables) {
    if (query.contains("workspace { people { id } }")) {
      final var people = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < existingPeople; i++) {
        people.add(OBJECT_MAPPER.createObjectNode().put("id", "person-" + i));
      }
      return workspaceWith("people", people);
    }
    if (query.contains("workspace { rooms { id name capacity } }")) {
      final var rooms = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < existingRoomNames.size(); i++) {
        rooms.add(
            OBJECT_MAPPER
                .createObjectNode()
                .put("id", "room-" + i)
                .put("name", existingRoomNames.get(i))
                .put("capacity", 10));
      }
      return workspaceWith("rooms", rooms);
    }
    if (query.contains("createPerson")) {
      final String name = String.valueOf(variables.get("name"));
      createdPeopleNames.add(name);
      // { person { ... }, errors }, the shape CreatePersonResult actually has. This fake used
      // to return id and name directly on the result, which the schema has never allowed - so
      // every unit test passed against a shape the API would reject. A fake confirms your
      // model of a dependency, not the dependency.
      final var person =
          OBJECT_MAPPER
              .createObjectNode()
              .put("id", UUID.randomUUID().toString())
              .put("name", name);
      final var payload = OBJECT_MAPPER.createObjectNode();
      payload.set("person", person);
      payload.set("errors", OBJECT_MAPPER.createArrayNode());
      return OBJECT_MAPPER.createObjectNode().set("createPerson", payload);
    }
    if (query.contains("createRoom")) {
      final String name = nestedString(variables, "room", "name");
      createdRoomNames.add(name);
      final var room =
          OBJECT_MAPPER
              .createObjectNode()
              .put("id", UUID.randomUUID().toString())
              .put("name", name)
              .put("capacity", 10);
      final var payload = OBJECT_MAPPER.createObjectNode();
      payload.set("room", room);
      return OBJECT_MAPPER.createObjectNode().set("createRoom", payload);
    }
    if (query.contains("DatesWithMeetings")) {
      // Every requested date comes back as a Day; the ones this fake was told already have
      // meetings come back non-empty. That IS the shape the real schema returns - a day with
      // no meetings is a present Day with an empty list, not an absent one - and getting it
      // wrong here would hide the difference between "unfetched" and "empty".
      final var days = OBJECT_MAPPER.createArrayNode();
      for (final Object date : (List<?>) variables.get("dates")) {
        final var meetings = OBJECT_MAPPER.createArrayNode();
        if (datesWithMeetings.contains(date.toString())) {
          meetings.add(OBJECT_MAPPER.createObjectNode().put("id", UUID.randomUUID().toString()));
        }
        days.add(
            OBJECT_MAPPER
                .createObjectNode()
                .put("date", date.toString())
                .set("meetings", meetings));
      }
      return workspaceWith("days", days);
    }
    if (query.contains("createMeetings")) {
      final String date = variables.get("date").toString();
      final List<?> batch = (List<?>) variables.get("meetings");
      createMeetingsCalls.add(
          new BulkCall(date, batch.stream().map(FakeGraphQlClient::subjectOf).toList()));
      final var failures = OBJECT_MAPPER.createArrayNode();
      for (int index = 0; index < batch.size(); index++) {
        if (rejectEverything || rejectSubjects.contains(subjectOf(batch.get(index)))) {
          final var errors = OBJECT_MAPPER.createArrayNode();
          errors.add("TimeRangeUnavailable");
          failures.add(OBJECT_MAPPER.createObjectNode().put("index", index).set("errors", errors));
        }
      }
      final var payload = OBJECT_MAPPER.createObjectNode();
      payload.set("failures", failures);
      return OBJECT_MAPPER.createObjectNode().set("createMeetings", payload);
    }
    throw new AssertionError("FakeGraphQlClient received an unexpected operation: " + query);
  }

  private static JsonNode workspaceWith(final String field, final JsonNode value) {
    final var workspace = OBJECT_MAPPER.createObjectNode();
    workspace.set(field, value);
    return OBJECT_MAPPER.createObjectNode().set("workspace", workspace);
  }

  @SuppressWarnings("unchecked")
  private static String subjectOf(final Object meetingInput) {
    return String.valueOf(((Map<String, Object>) meetingInput).get("subject"));
  }

  @SuppressWarnings("unchecked")
  private static String nestedString(
      final Map<String, Object> variables, final String outer, final String field) {
    return ((Map<String, Object>) variables.get(outer)).get(field).toString();
  }
}
