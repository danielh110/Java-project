package io.leangen.graphql;

import static io.leangen.graphql.support.QueryResultAssertions.assertNoErrors;

import java.util.List;

import org.eclipse.microprofile.graphql.Argument;
import org.eclipse.microprofile.graphql.Query;
import org.junit.Test;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.batched.BatchedExecutionStrategy;
import graphql.schema.GraphQLSchema;
import io.leangen.graphql.domain.JsonPatch;

public class ObjectScalarTest {

    public static class PatchService {

        @Query(value = "processPatches")
        public List<JsonPatch> processPatches(@Argument(value = "args") List<JsonPatch> patches) {
            return patches;
        }
    }

    @Test
    public void testObjectScalar() {
        GraphQLSchema schema = new TestSchemaGenerator()
                .withOperationsFromSingleton(new PatchService())
                .generate();

        GraphQL exe = GraphQL.newGraphQL(schema).queryExecutionStrategy(new BatchedExecutionStrategy()).build();
        ExecutionResult result = exe.execute("{processPatches(args: {op: replace, path: \"/active\", value: 1}){op}}");
        assertNoErrors(result);
    }
}
