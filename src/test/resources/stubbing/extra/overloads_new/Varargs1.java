// src/test/resources/stubbing/extra/overloads_new/Varargs1.java
package fixtures.over;

class Varargs1 {
    void x() {
        Target.m("a", "b");   // Target is missing on purpose
    }
}
