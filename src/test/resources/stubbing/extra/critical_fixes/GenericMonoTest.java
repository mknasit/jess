package fixtures.critical;

import reactor.core.publisher.Mono;

class GenericMonoTest {
    @TargetMethod
    Mono<String> processMono(Mono<String> input) {
        return input.map(s -> s.toUpperCase());
    }
}

