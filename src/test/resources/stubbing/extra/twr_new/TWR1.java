package fixtures.twr;
class R implements java.lang.AutoCloseable { public void close(){} }
class TWR1 { void m(){ try(R r = new R()){} catch(Exception e){} } }
