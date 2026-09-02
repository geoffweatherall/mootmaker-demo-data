package com.mootmaker.demodata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import module java.base;

/**
 * A {@link GraphQlClient} that answers reads from a fixed in-memory world and records writes,
 * so the top-up arithmetic can be tested without a deployed environment. Only the operations
 * {@link DemoData}'s people and rooms concerns actually issue are understood; anything else fails
 * loudly rather than silently returning nothing.
 */
final class FakeGraphQlClient extends GraphQlClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final int existingPeople;
    private final List<String> existingRoomNames;
    private final List<String> createdPeopleNames = Collections.synchronizedList(new ArrayList<>());
    private final List<String> createdRoomNames = Collections.synchronizedList(new ArrayList<>());

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
        if (query.contains("query { people { id } }")) {
            final var people = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < existingPeople; i++) {
                people.add(OBJECT_MAPPER.createObjectNode().put("id", "person-" + i));
            }
            return OBJECT_MAPPER.createObjectNode().set("people", people);
        }
        if (query.contains("query { rooms { id name capacity } }")) {
            final var rooms = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < existingRoomNames.size(); i++) {
                rooms.add(OBJECT_MAPPER.createObjectNode()
                        .put("id", "room-" + i)
                        .put("name", existingRoomNames.get(i))
                        .put("capacity", 10));
            }
            return OBJECT_MAPPER.createObjectNode().set("rooms", rooms);
        }
        if (query.contains("createPerson")) {
            final String name = nestedString(variables, "person", "name");
            createdPeopleNames.add(name);
            return OBJECT_MAPPER.createObjectNode().set("createPerson",
                    OBJECT_MAPPER.createObjectNode().put("id", UUID.randomUUID().toString()).put("name", name));
        }
        if (query.contains("createRoom")) {
            final String name = nestedString(variables, "room", "name");
            createdRoomNames.add(name);
            final var room = OBJECT_MAPPER.createObjectNode()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", name)
                    .put("capacity", 10);
            final var payload = OBJECT_MAPPER.createObjectNode();
            payload.set("room", room);
            return OBJECT_MAPPER.createObjectNode().set("createRoom", payload);
        }
        throw new AssertionError("FakeGraphQlClient received an unexpected operation: " + query);
    }

    @SuppressWarnings("unchecked")
    private static String nestedString(final Map<String, Object> variables, final String outer, final String field) {
        return ((Map<String, Object>) variables.get(outer)).get(field).toString();
    }
}
