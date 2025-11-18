package fixtures.records;

class RecordConstructorTest {
    @TargetMethod
    User createUser(String name, int age, String email) {
        return new User(name, age, email);
    }
    
    @TargetMethod
    Point createPoint(int x, int y) {
        return new Point(x, y);
    }
    
    @TargetMethod
    Address createAddress(String street, String city, String zip) {
        return new Address(street, city, zip);
    }
}

