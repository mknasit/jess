package fixtures.critical;

import java.util.Optional;

class GenericOptionalTest {
    @TargetMethod
    String processOptional(Optional<String> opt) {
        return opt.map(s -> s.toUpperCase())
                  .orElse("DEFAULT");
    }
}

