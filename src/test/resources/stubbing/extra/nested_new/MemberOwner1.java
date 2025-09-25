package fixtures.nested1;
class Outer { static class Inner { int f; void set(int x){ f=x; } } }
class MemberOwner1 { void m(){ Outer.Inner in = new Outer.Inner(); in.set(1); } }
