package com.mootmaker.demodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import module java.base;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every operation in {@link Operations} must be one mootmaker-api's schema accepts
 * (mootmaker-demo-data#23).
 *
 * <p>Enumerated by reflection, so a new constant is covered without anyone remembering to add it
 * here. Validated as documents: the syntax, the selection and the declared variable types. The
 * variables actually sent are checked separately, by {@code FakeGraphQlClient}, on every operation
 * the other tests drive through it.
 */
class OperationsSchemaTest {

  @TestFactory
  Stream<DynamicTest> everyOperationIsValidAgainstTheApiSchema() {
    return operations().entrySet().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey(),
                    () -> assertEquals(List.of(), ApiSchema.problemsWith(entry.getValue()))));
  }

  /** Proves the guard can fail - a check that never rejects anything proves nothing. */
  @Test
  void anOperationTheSchemaDoesNotAllowIsRejected() {
    // The exact shape that was broken for days: CreatePersonResult has no id on it.
    assertFalse(
        ApiSchema.problemsWith(
                "mutation CreatePerson($name: String!) { createPerson(name: $name) { id } }")
            .isEmpty());
  }

  @Test
  void aVariableOfTheWrongShapeIsRejected() {
    assertFalse(
        ApiSchema.problemsWith(Operations.CREATE_ROOM, Map.of("room", Map.of("name", "x")))
            .isEmpty(),
        "RoomInput without its required capacity should be refused");
  }

  private static Map<String, String> operations() {
    final Map<String, String> operations = new TreeMap<>();
    for (final Field field : Operations.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        try {
          field.setAccessible(true);
          operations.put(field.getName(), (String) field.get(null));
        } catch (final IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    assertFalse(operations.isEmpty(), "found no operations to check");
    return operations;
  }
}
