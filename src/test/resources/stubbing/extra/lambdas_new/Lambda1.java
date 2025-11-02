package fixtures.lambda;

class Lambda1 {
    int m() {
        F<Integer> f = (Integer i) -> i + 1;
        return f.apply(1);
    }
}
