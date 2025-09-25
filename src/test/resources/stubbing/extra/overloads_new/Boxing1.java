package fixtures.over;
class T { void m(long x){} }
class Boxing1 { void k(){ new T().m(1); } } // int -> long widening
