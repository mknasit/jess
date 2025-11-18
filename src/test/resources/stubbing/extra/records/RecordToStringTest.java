package fixtures.records;

class RecordToStringTest {
    @TargetMethod
    String recordToString(User user) {
        return user.toString();
    }
    
    @TargetMethod
    String pointToString(Point point) {
        return point.toString();
    }
}

