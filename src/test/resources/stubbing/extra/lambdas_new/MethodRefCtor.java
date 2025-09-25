package fixtures.lambda2;
class A{ A(){} }
interface Maker { A make(); }
class MethodRefCtor { A build(){ Maker m = A::new; return m.make(); } }
