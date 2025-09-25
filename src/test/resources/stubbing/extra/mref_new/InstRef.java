package fixtures.mref2;
class B{ int plus(int x){ return x+1; } }
interface F { int apply(int x); }
class InstRef { int k(){ B b = new B(); F f = b::plus; return f.apply(1); } }
