package fixtures.mref;
class A{ static int inc(int x){ return x+1; } }
interface F { int apply(int x); }
class StaticRef { int k(){ F f = A::inc; return f.apply(1); } }
