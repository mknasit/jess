// TWR1.java  (testdata, fixed)
package fixtures.twr;

class TWR1 {
    void m() {
        try (R r = new R()) {
            // body
        } catch (Exception e) {
        }
    }
}
