package fixtures.enums_enhanced;

class EnumMethodsTest {
    @TargetMethod
    int useEnumMethods(Priority priority) {
        int ordinal = priority.ordinal();
        int compare = priority.compareTo(Priority.HIGH);
        String name = priority.name();
        return ordinal + compare;
    }
}

