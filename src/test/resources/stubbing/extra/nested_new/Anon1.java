package fixtures.nested2;
interface X { int go(); }
class Anon1 { int z(){ X x = new X(){ public int go(){ return 1; } }; return x.go(); } }
