package fixtures.critical;

class BuilderPatternTest {
    @TargetMethod
    BuilderPatternTest create() {
        return builder().get();
    }
    
    static Builder builder() {
        return new Builder();
    }
    
    static class Builder {
        BuilderPatternTest get() {
            return new BuilderPatternTest();
        }
    }
}

