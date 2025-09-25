package fixtures.gen;
import java.util.List;
class G { void m(List<? extends Number> x){} }
class Use { void u(java.util.List<Integer> xs){ new G().m(xs); } }
