package fixtures.innerreq;

import ext.outer.Outer;

class Use {
    Outer.Inner make() {
        Outer o = new Outer();
        return o.new Inner(3); // must be a member class of Outer
    }
}
