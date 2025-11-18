package fixtures.modern;

class OptionalTest {
    @TargetMethod
    String processOptional(Maybe<String> opt) {
        return opt.map(s -> s.toUpperCase())
                  .orElse("DEFAULT");
    }
}

