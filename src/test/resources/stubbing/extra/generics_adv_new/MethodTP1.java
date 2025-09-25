package fixtures.gen2;
interface C { void close(); }
class U { 
    public static <T extends C> T id(T t){ return t; }
    static void k(){ C c = id(new C(){ public void close(){} }); }
}
