package fixtures.records;

class RecordEqualsTest {
    @TargetMethod
    boolean compareRecords(User user1, User user2) {
        boolean equals = user1.equals(user2);
        int hashCode1 = user1.hashCode();
        int hashCode2 = user2.hashCode();
        return equals && (hashCode1 == hashCode2);
    }
    
    @TargetMethod
    boolean comparePoints(Point p1, Point p2) {
        return p1.equals(p2);
    }
}

