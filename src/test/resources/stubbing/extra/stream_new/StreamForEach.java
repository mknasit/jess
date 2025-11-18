package fixtures.stream;

class StreamForEach {
    @TargetMethod
    void process(ItemList items) {
        items.stream().forEach(item -> System.out.println(item));
    }
}

