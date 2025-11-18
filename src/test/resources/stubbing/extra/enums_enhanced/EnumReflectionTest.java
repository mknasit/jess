package fixtures.enums_enhanced;

class EnumReflectionTest {
    @TargetMethod
    boolean checkEnumType(Object obj) {
        if (obj instanceof java.lang.Enum) {
            java.lang.Enum<?> enumObj = (java.lang.Enum<?>) obj;
            Class<?> enumClass = enumObj.getDeclaringClass();
            return enumClass.isEnum();
        }
        return false;
    }
}

