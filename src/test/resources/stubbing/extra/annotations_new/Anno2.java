// src/test/resources/stubbing/extra/annotations_new/Anno2.java
package fixtures.anno2;

class Box<T> {}

class Anno2 {
    Box<@TA String> b;
}
