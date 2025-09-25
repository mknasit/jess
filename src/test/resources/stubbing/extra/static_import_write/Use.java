package fixtures.staticwrite;

import static extlib.Lib.COUNT;

class Use {
    int k() {
        COUNT = 5;     // <— write through static import
        return COUNT;
    }
}
