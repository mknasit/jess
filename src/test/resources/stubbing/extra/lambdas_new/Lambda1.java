package fixtures.lambda;
class Lambda1 { int m(){ F<Integer> f = (Integer i) -> i+1; return f.apply(1); } }
interface F<T>{ T apply(T t); }
