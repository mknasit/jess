package fixtures.bridge;
interface Box<T>{ T get(); }
class S implements Box<String> { public String get(){ return "x"; } }
class Use { String s(){ return new S().get(); } }
