package fixtures.ifacesuper;

interface A {
    default String m() { return "A"; }
}
interface B extends A {
    default String m() { return "B"; }
    default String call() { return A.super.m(); } // <— requires qualified super
}
class Use implements B {
    String s() { return call(); }
}
