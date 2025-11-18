package fixtures.stream;

class StreamMap {
    @TargetMethod
    IntList mapToStringLengths(StringList items) {
        return items.stream()
                .map(String::length)
                .collect(IntListCollector.toList());
    }
}

