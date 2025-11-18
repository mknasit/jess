package fixtures.stream;

class CollectionForEach {
    @TargetMethod
    void processItems(StringCollection items) {
        items.forEach(item -> System.out.println(item.toUpperCase()));
    }
}

