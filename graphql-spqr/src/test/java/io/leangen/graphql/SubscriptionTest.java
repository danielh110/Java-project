package io.leangen.graphql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLSchema;
import io.leangen.graphql.annotations.Subscription;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Observable;

public class SubscriptionTest {

    @Test
    public void subscriptionTest() {

        GraphQLSchema schema = new GraphQLSchemaGenerator()
                .withOperationsFromSingleton(new Ticker())
                .generate();

        List<GraphQLFieldDefinition> subscriptions = schema.getSubscriptionType().getFieldDefinitions();
        assertEquals(1, subscriptions.size());
        assertSame(Scalars.GraphQLInt, subscriptions.get(0).getType());
        GraphQL exe = GraphQL.newGraphQL(schema).build();

        ExecutionResult res = exe.execute("subscription Tick { tick }");
        Publisher<ExecutionResult> stream = res.getData();
        //doesn't actually need to be atomic, but needs to be effectively final yet mutable
        AtomicInteger counter = new AtomicInteger(0);
        AtomicBoolean complete = new AtomicBoolean(false);
        stream.subscribe(new Subscriber<ExecutionResult>() {
            @Override
            public void onSubscribe(org.reactivestreams.Subscription subscription) {
                subscription.request(10);
            }

            @Override
            public void onNext(ExecutionResult executionResult) {
                counter.getAndIncrement();
            }

            @Override
            public void onError(Throwable throwable) {
                fail();
            }

            @Override
            public void onComplete() {
                complete.set(true);
            }
        });
        assertTrue(complete.get());
        assertEquals(2, counter.get());
    }

    public static class Ticker {

        @Subscription
        public Publisher<Integer> tick() {
            Observable<Integer> observable = Observable.create(emitter -> {
                emitter.onNext(1);
                Thread.sleep(1000);
                emitter.onNext(2);
                Thread.sleep(1000);
                emitter.onComplete();
            });

            return observable.toFlowable(BackpressureStrategy.BUFFER);
        }
    }
}
