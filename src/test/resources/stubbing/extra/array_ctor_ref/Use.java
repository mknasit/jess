package fixtures.arrctor;

import ext.arr.ArrMaker;

class Use {
    String[] go() {
        ArrMaker<String> m = String[]::new;
        return m.make(3);
    }
}
