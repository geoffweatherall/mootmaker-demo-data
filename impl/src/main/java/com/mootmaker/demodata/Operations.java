package com.mootmaker.demodata;

/**
 * Every GraphQL operation this tool sends, in one place so they can be checked against the API's
 * schema without a deployed environment (mootmaker-demo-data#23).
 *
 * <p>Previously each was an inline string where it was used, and nothing compared any of them with
 * what the API accepts: the unit tests answer from {@code FakeGraphQlClient}, which encodes our
 * model of the API rather than the API. That is how four separate breakages stayed green in CI for
 * days. {@code OperationsSchemaTest} now validates every constant here against {@code
 * mootmaker-api}'s schema, and the fake validates each query it is sent, so an operation added
 * inline somewhere else is caught too.
 */
final class Operations {

  private Operations() {}

  // CreatePersonResult has never had id or name directly on it - the selection goes through
  // person, matching createRoom below.
  static final String CREATE_PERSON =
      "mutation CreatePerson($name: String!) { "
          + "createPerson(name: $name) { person { id name } errors } }";

  static final String REQUEST_AVATAR_UPLOAD =
      "mutation RequestAvatarUpload($personId: ID!, $contentType: String!,"
          + " $contentLength: Int!) { requestAvatarUpload(personId: $personId,"
          + " contentType: $contentType, contentLength: $contentLength) {"
          + " upload { uploadId url } errors } }";

  static final String CONFIRM_AVATAR_UPLOAD =
      "mutation ConfirmAvatarUpload($personId: ID!, $uploadId: ID!) {"
          + " confirmAvatarUpload(personId: $personId, uploadId: $uploadId) {"
          + " person { id avatarUrl } errors } }";

  static final String CREATE_ROOM =
      "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity"
          + " } errors } }";

  static final String ROOMS = "query { workspace { rooms { id name capacity } } }";

  static final String PEOPLE_AVATAR_URLS = "query { workspace { people { id avatarUrl } } }";

  static final String PERSON_IDS = "query { workspace { people { id } } }";

  static final String DATES_WITH_MEETINGS =
      "query DatesWithMeetings($dates: [String!]) { "
          + "workspace(dates: $dates) { days { date meetings { id } } } }";

  static final String MEETING_DETAILS =
      "query MeetingDetails($dates: [String!]) { "
          + "workspace(dates: $dates) { days { date meetings { "
          + "room { id } organiser { id } attendees { person { id } } startTime endTime } } } }";

  static final String CREATE_MEETINGS =
      "mutation CreateMeetings($date: String!, $meetings: [MeetingInput!]!) { "
          + "createMeetings(date: $date, meetings: $meetings) { failures { index errors } } }";
}
