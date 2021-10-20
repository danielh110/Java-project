package io.smallrye.graphql.client.typesafe.api;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import java.util.List;

import io.smallrye.graphql.client.Error;

/**
 * Represents a response that contained application-level errors and thus can't be turned into a domain object.
 * Thrown by the typesafe client upon an invocation.
 * This does NOT represent a response that can't be properly parsed, only a response that contains
 * application-level errors.
 */
public class GraphQLClientException extends RuntimeException {
    private final List<Error> errors;

    public GraphQLClientException(String message, List<Error> errors) {
        super(message);
        this.errors = requireNonNull(errors);
    }

    @Override
    public String toString() {
        return "GraphQlClientException: " + getMessage() + (errors.isEmpty() ? ""
                : "\nerrors:\n- " + errors.stream().map(Error::toString).collect(joining("\n- "))) + ")";
    }

    public List<Error> getErrors() {
        return errors;
    }
}
