package fixtures.modern;

interface BaseProcessor<T> {
    T process(T input);
}

interface AdvancedProcessor<T> extends BaseProcessor<T> {
    T advancedProcess(T input);
}

class GenericExtends {
    @TargetMethod
    String useProcessor(AdvancedProcessor<String> processor, String input) {
        return processor.advancedProcess(processor.process(input));
    }
}

