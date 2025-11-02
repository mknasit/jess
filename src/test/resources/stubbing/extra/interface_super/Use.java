package fixtures.ifacesuper;

import ext.iface.B;

class Use implements B {
    String s() { return call(); } // calls B.default call(), which does A.super.m()
}
