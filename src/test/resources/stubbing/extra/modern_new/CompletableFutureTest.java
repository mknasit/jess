package fixtures.modern;

class CompletableFutureTest {
    @TargetMethod
    AsyncResult<String> processAsync(AsyncResult<Integer> future) {
        return future.thenApply(i -> "Result: " + i);
    }
}

