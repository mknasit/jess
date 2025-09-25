package fixtures.innerreq;

class Use {
    Outer.Inner make() {
        Outer o = new Outer();
        return o.new Inner(3);
    }
}
