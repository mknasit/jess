package fixtures.enums_enhanced;

import java.util.EnumSet;

class EnumSetTest {
    @TargetMethod
    void useEnumSet(State state) {
        EnumSet<State> allStates = EnumSet.allOf(State.class);
        EnumSet<State> someStates = EnumSet.of(State.ACTIVE, State.IDLE);
        boolean contains = allStates.contains(state);
        someStates.add(State.INACTIVE);
        someStates.remove(State.IDLE);
    }
}

