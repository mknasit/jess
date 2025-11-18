package fixtures.stream;

class StreamFilter {
    @TargetMethod
    StringList filterLongStrings(StringList items) {
        return items.stream()
                .filter(s -> s.length() > 5)
                .collect(StringListCollector.toList());
    }
}

