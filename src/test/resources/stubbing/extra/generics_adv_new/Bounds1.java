
package fixtures.gen;

import java.util.List;

class Use {
    void u(List<Integer> xs) {
        // G is NOT defined anywhere in this file on purpose.
        // The tool must stub a class `G` with a method taking List<? extends Number>
        new G().m(xs);
}
