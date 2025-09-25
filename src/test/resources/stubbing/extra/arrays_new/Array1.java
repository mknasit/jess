package fixtures.arr;
class E { int v(){ return 1; } }
class Array1 { int k(){ E[][] a = new E[1][1]; if(a[0][0]==null) return 0; return a[0][0].v(); } }
