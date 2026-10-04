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
  // Keyed by the id createPerson handed back. createPerson and the avatar upload for the same
  // person happen on one worker thread but interleave with other people's, so everything about a
  // person hangs off their id rather than off the order calls arrived in.
  private final Map<String, String> createdNamesById = new ConcurrentHashMap<>();
  private final Map<String, String> avatarHashByPersonId = new ConcurrentHashMap<>();
  private final Map<String, Upload> uploadsById = new ConcurrentHashMap<>();
  private final List<String> existingAvatarUrls = new ArrayList<>();
  private String rejectConfirmWith;

  /** One created person - {@code avatarSha256} is null when no avatar was set. */
  record CreatedPerson(String name, String avatarSha256) {}

  /** One avatar upload, as the three calls described it. */
  static final class Upload {
    final String personId;
    final String declaredContentType;
    final int declaredContentLength;
    volatile String putContentType;
    volatile byte[] putBytes;
    volatile boolean confirmed;

    Upload(final String personId, final String contentType, final int contentLength) {
      this.personId = personId;
      this.declaredContentType = contentType;
      this.declaredContentLength = contentLength;
    }
  }

  List<Upload> uploads() {
    return List.copyOf(uploadsById.values());
  }

  /**
   * Gives existing people avatars with these hashes, as the real API would report them: absolute
   * URLs, each under its own person's path. The first {@code hashes.size()} people get one.
   */
  void withExistingAvatarHashes(final Collection<String> hashes) {
    int index = 0;
    for (final String hash : hashes) {
      existingAvatarUrls.add(
          "https://avatars.example.test/v1/person-" + index++ + "/" + hash + ".jpg");
    }
  }

  /** Makes confirmAvatarUpload reject, to exercise the failure path. */
  void rejectingConfirmWith(final String error) {
    rejectConfirmWith = error;
  }

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
    return List.copyOf(createdNamesById.values());
  }

  List<CreatedPerson> createdPeople() {
    return createdNamesById.entrySet().stream()
        .map(entry -> new CreatedPerson(entry.getValue(), avatarHashByPersonId.get(entry.getKey())))
        .toList();
  }

  /** Stands in for S3: accepts the bytes against the upload the URL names. */
  @Override
  void put(final String url, final String contentType, final byte[] body) {
    final Upload upload = uploadsById.get(url.substring(url.lastIndexOf('/') + 1));
    if (upload == null) {
      throw new AssertionError("PUT to a URL no requestAvatarUpload issued: " + url);
    }
    // What S3 enforces from the signature: exactly the declared type, exactly the declared length.
    if (!contentType.equals(upload.declaredContentType)
        || body.length != upload.declaredContentLength) {
      throw new IllegalStateException("Upload was refused with HTTP 403: SignatureDoesNotMatch");
    }
    upload.putContentType = contentType;
    upload.putBytes = body;
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
    // Before anything else: would the real API accept this request at all? Without this the fake
    // answers whatever it is asked in whatever shape it believes, which is how four breakages
    // stayed green (mootmaker-demo-data#23).
    final List<String> problems = ApiSchema.problemsWith(query, variables);
    if (!problems.isEmpty()) {
      throw new AssertionError("The API's schema rejects this request: " + problems + "\n" + query);
    }
    if (query.contains("workspace { people { id } }")) {
      final var people = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < existingPeople; i++) {
        people.add(OBJECT_MAPPER.createObjectNode().put("id", "person-" + i));
      }
      return workspaceWith("people", people);
    }
    if (query.contains("workspace { people { id avatarUrl } }")) {
      final var people = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < existingPeople; i++) {
        final var person = OBJECT_MAPPER.createObjectNode().put("id", "person-" + i);
        if (i < existingAvatarUrls.size()) {
          person.put("avatarUrl", existingAvatarUrls.get(i));
        } else {
          person.putNull("avatarUrl");
        }
        people.add(person);
      }
      return workspaceWith("people", people);
    }
    if (query.contains("requestAvatarUpload")) {
      final String personId = String.valueOf(variables.get("personId"));
      if (!createdNamesById.containsKey(personId)) {
        throw new AssertionError("requestAvatarUpload for a person never created: " + personId);
      }
      final String uploadId = UUID.randomUUID().toString();
      uploadsById.put(
          uploadId,
          new Upload(
              personId,
              String.valueOf(variables.get("contentType")),
              ((Number) variables.get("contentLength")).intValue()));
      final var upload =
          OBJECT_MAPPER
              .createObjectNode()
              .put("uploadId", uploadId)
              .put("url", "https://s3.example.test/uploads/" + personId + "/" + uploadId);
      final var payload = OBJECT_MAPPER.createObjectNode();
      payload.set("upload", upload);
      payload.set("errors", OBJECT_MAPPER.createArrayNode());
      return OBJECT_MAPPER.createObjectNode().set("requestAvatarUpload", payload);
    }
    if (query.contains("confirmAvatarUpload")) {
      final String personId = String.valueOf(variables.get("personId"));
      final Upload upload = uploadsById.get(String.valueOf(variables.get("uploadId")));
      final var payload = OBJECT_MAPPER.createObjectNode();
      final var errors = OBJECT_MAPPER.createArrayNode();
      payload.set("errors", errors);
      if (upload == null || !upload.personId.equals(personId) || upload.putBytes == null) {
        errors.add("UploadNotFound");
        payload.putNull("person");
      } else if (rejectConfirmWith != null) {
        errors.add(rejectConfirmWith);
        payload.putNull("person");
      } else {
        // Keyed by the hash of the bytes as uploaded, exactly as the real API keys them.
        final String hash = sha256Hex(upload.putBytes);
        upload.confirmed = true;
        avatarHashByPersonId.put(personId, hash);
        payload.set(
            "person",
            OBJECT_MAPPER
                .createObjectNode()
                .put("id", personId)
                .put(
                    "avatarUrl",
                    "https://avatars.example.test/v1/" + personId + "/" + hash + ".jpg"));
      }
      return OBJECT_MAPPER.createObjectNode().set("confirmAvatarUpload", payload);
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
      // The schema declares exactly one argument. The real API would refuse anything else before
      // a resolver ran, so this fake does too, rather than politely ignoring it.
      if (!variables.keySet().equals(Set.of("name")) || query.contains("avatarUrl:")) {
        throw new AssertionError("createPerson takes only a name, got: " + variables.keySet());
      }
      final String id = UUID.randomUUID().toString();
      createdNamesById.put(id, name);
      // { person { ... }, errors }, the shape CreatePersonResult actually has. This fake used
      // to return id and name directly on the result, which the schema has never allowed - so
      // every unit test passed against a shape the API would reject. A fake confirms your
      // model of a dependency, not the dependency.
      final var person = OBJECT_MAPPER.createObjectNode().put("id", id).put("name", name);
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

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
