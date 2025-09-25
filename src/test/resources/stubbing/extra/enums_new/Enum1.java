package fixtures.enums1;
enum FS { A, B; }
class Enum1 {
    String m() {
        FS[] all = FS.values();
        FS one = FS.valueOf("A");
        return one.name();
    }
}
