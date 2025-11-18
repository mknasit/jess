package fixtures.records;

class RecordComponentsTest {
    @TargetMethod
    String useRecordComponents(User user) {
        String name = user.name();
        int age = user.age();
        String email = user.email();
        return name + " (" + age + ") - " + email;
    }
    
    @TargetMethod
    Point createPoint(int x, int y) {
        return new Point(x, y);
    }
    
    @TargetMethod
    int getPointX(Point point) {
        return point.x();
    }
}

