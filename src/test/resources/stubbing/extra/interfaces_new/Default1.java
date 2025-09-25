package fixtures.iface;
interface I { default String hi(){ return "x"; } }
class C implements I { String s(){ return hi(); } }
