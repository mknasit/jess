package fixtures.records;

class NestedRecordTest {
    @TargetMethod
    void useNestedRecord() {
        Outer.InnerRecord inner = new Outer.InnerRecord("value");
        String data = inner.data();
    }
}

class Outer {
    record InnerRecord(String data) {
    }
}

