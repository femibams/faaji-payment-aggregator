package dev.faaji.streams.api.v1;

import dev.faaji.streams.api.v1.domain.User;
import dev.faaji.streams.api.v1.domain.UserMatch;
import dev.faaji.streams.model.TotalView;
import dev.faaji.streams.service.UserMatchStreamProcessor;
import dev.faaji.streams.service.bindings.StreamBindings;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.faaji.streams.service.bindings.MaterialBinding.EVENT_ATTENDEE_STORE;
import static dev.faaji.streams.service.bindings.MaterialBinding.USER_INTEREST_STORE;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class PaymentApiEndpoint {

    private final InteractiveQueryService queryService;
    private final UserMatchStreamProcessor processor;

    public PaymentApiEndpoint(InteractiveQueryService queryService, UserMatchStreamProcessor processor) {
        this.queryService = queryService;
        this.processor = processor;
    }

    @Bean
    public RouterFunction<ServerResponse> handleRoutes() {
        return route()
                .path("/api/v1", base -> base
                        .GET("/payment/{eventId}", request -> {
                            var eventId = request.pathVariable("eventId");
                            ReadOnlyKeyValueStore<String, TotalView> store = queryService.getQueryableStore(
                                    StreamBindings.PAYMENT_TOTAL_STATE_STORE, QueryableStoreTypes.keyValueStore());
                            Mono<TotalView> event = Mono.fromCallable(() -> store.get(eventId))
                                    .switchIfEmpty(Mono.error(Exceptions.propagate(new ResponseStatusException(HttpStatus.NOT_FOUND, "Event Not Found"))));

                            return ServerResponse.ok()
                                    .body(event, TotalView.class);
                        })
                        .GET("/event/{eventId}", request -> {
                            var eventId = request.pathVariable("eventId");
                            var queue = getMatchQueue(eventId);
                            return ServerResponse.ok()
                                    .bodyValue(EventResponse.withMetadata(eventId, queue, Map.of("size", queue.size())));
                        })
                        .GET("/user/{id}", request -> {
                            var userId = request.pathVariable("id");
                            ReadOnlyKeyValueStore<String, List<String>> store = getQueryableStore(USER_INTEREST_STORE);
                            List<String> data = Optional.ofNullable(store.get(userId)).orElse(List.of("none"));
                            return ServerResponse.ok()
                                    .bodyValue(EventResponse.from(userId, data));
                        })
                        .POST("/event/{eventId}/start", request -> {
                            var eventId = request.pathVariable("eventId");
                            var queue = getMatchQueue(eventId);
                            processor.setMatchMatrix(queue, eventId);
                            return ServerResponse.ok().build();
                        })
                        .GET("/event/{eventId}/start/{round}", request -> {
                            var eventId = request.pathVariable("eventId");
                            var round = Integer.parseInt(request.pathVariable("round"));
                            List<UserMatch> matchQueue = processor.getRound(getMatchQueue(eventId), eventId, round);
                            return ServerResponse.ok().bodyValue(matchQueue);
                        })
                ).route(RequestPredicates.all(), request -> ServerResponse.status(HttpStatus.FORBIDDEN).build()).build();

    }

    private <K, V> ReadOnlyKeyValueStore<K, V> getQueryableStore(String name) {
        try {
            return queryService.getQueryableStore(name, QueryableStoreTypes.keyValueStore());
        } catch (InvalidStateStoreException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The requested resource is being prepared");
        }
    }

    private List<User> getMatchQueue(String eventId) {
        ReadOnlyKeyValueStore<String, List<String>> store = getQueryableStore(EVENT_ATTENDEE_STORE);
        ReadOnlyKeyValueStore<String, List<String>> interestStore = getQueryableStore(USER_INTEREST_STORE);
        List<String> data = Optional.ofNullable(store.get(eventId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "data not found"));
        return data.stream().map(dt -> {
            var userData = dt.split(":");
            var in = interestStore.get("%s:%s".formatted(eventId, userData[0]));
            var ins = in != null ? in.toArray(new String[]{}) : new String[]{};
            return new User(userData[0], userData.length == 2 ? userData[1] : "non-binary", ins);
        }).toList();
    }
}
