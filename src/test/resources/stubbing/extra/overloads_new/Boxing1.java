// src/test/resources/stubbing/extra/overloads_new/Boxing1.java
package fixtures.over;

class Boxing1 {
    void k() {
        new T().m(1);   // T is missing on purpose; call site uses int literal
    }
}
