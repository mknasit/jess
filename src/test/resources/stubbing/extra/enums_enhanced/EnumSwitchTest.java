package fixtures.enums_enhanced;

class EnumSwitchTest {
    @TargetMethod
    void switchOnEnum(Status status) {
        switch (status) {
            case PENDING:
                break;
            case RUNNING:
                break;
            case COMPLETED:
                break;
            case FAILED:
                break;
            default:
                break;
        }
    }
}

