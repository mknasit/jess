package fixtures.anno2;
@interface TA {}
class Box<T>{}
class Anno2 { Box<@TA String> b; }
