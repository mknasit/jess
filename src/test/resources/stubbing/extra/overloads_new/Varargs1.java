package fixtures.over;
class Target { static void m(String... s){} }
class Varargs1 { void x(){ Target.m("a","b"); } }
