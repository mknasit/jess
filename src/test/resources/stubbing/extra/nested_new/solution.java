//anno1
package fixtures.nested1;

public class Outer {
    public static class Inner {
        public void set(int x) {
        }
    }
}

// Member owner
package fixtures.nested2;

public interface X {
    int go();
}


//testform

//Short answer: no, that’s not what you want. Your tool is *close* (it saw the missing types), but it missed the two key structural bits:
//
//1. **the owner/containing class & package** for `Outer.Inner`
//2. **that `X` is an interface in the same package**, not a concrete class in `unknown`
//
//Let me show you what’s off and what it should look like.
//
//---
//
//## 1) `MemberOwner1` case
//
//### What your tool produced
//
//```java
//package Outer;
//public class Inner {
//    public void set(int arg0) {
//    }
//}
//
//package fixtures.nested1;
//import unknown.*;
//class MemberOwner1 {
//    void m() {
//        Outer.Inner in = new Outer.Inner();
//        in.set(1);
//    }
//}
//```
//
//### Problems
//
//1. **Wrong package for the stub.**
//   The user code is in `package fixtures.nested1;`, and it refers to `Outer.Inner` **without** an import, so it expects `Outer` to be in the **same package**: `fixtures.nested1.Outer`.
//   You put it in `package Outer;` — that makes it a top-level package, totally different.
//
//2. **You created `Inner` as a top-level type, not as a member of `Outer`.**
//   The code says `new Outer.Inner()`, which means: “there is a class `Outer`, and **inside it** there is a `static class Inner`.”
//   Your tool made **two separate top-level things**: a package `Outer` and a class `Inner` — that does **not** match `Outer.Inner`.
//
//3. **Spurious `import unknown.*;`**
//   There’s no need for this; it just adds noise.
//
//### What it should generate
//
//You want exactly one top-level class `Outer` **in the same package** as the usage, and inside it a `public static class Inner` with the method:
//
//```java
//package fixtures.nested1;
//
//public class Outer {
//    public static class Inner {
//        public void set(int x) {
//        }
//    }
//}
//```
//
//Now your original user code:
//
//```java
//package fixtures.nested1;
//
//class MemberOwner1 {
//    void m() {
//        Outer.Inner in = new Outer.Inner();
//        in.set(1);
//    }
//}
//```
//
//will resolve fine.
//
//---
//
//## 2) `Anon1` (anonymous class) case
//
//### What your tool produced
//
//```java
//package unknown;
//public class X {
//    public X() {
//    }
//
//    public int go() {
//        return 0;
//    }
//}
//
//package fixtures.nested2;
//import unknown.*;
//class Anon1 {
//    int z() {
//        X x = new X() {
//            public int go() {
//                return 1;
//            }
//        };
//        return x.go();
//    }
//}
//```
//
//### Problems
//
//1. **Wrong package again.**
//   The file is `package fixtures.nested2;` and it says `X x = new X() { ... };` **without** an import, so `X` is expected to be in `fixtures.nested2`. You put it in `package unknown;`.
//
//2. **You made `X` a concrete class, not an interface.**
//   When code does:
//
//   ```java
//   X x = new X() {
//       public int go() { return 1; }
//   };
//   ```
//
//   that is the classic “implement missing interface” or “extend abstract class” pattern. Making `X` a normal concrete class with a non-abstract `go()` works at runtime for the anonymous class, but it’s **not** what this test is supposed to check — it’s supposed to check “missing *interface* behind an anonymous class.”
//
//3. **Unnecessary import.**
//   `import unknown.*;` shows up because you shoved `X` in another package, but really it should just live right there.
//
//### What it should generate
//
//Just this:
//
//```java
//package fixtures.nested2;
//
//public interface X {
//    int go();
//}
//```
//
//Then the original testdata:
//
//```java
//package fixtures.nested2;
//
//class Anon1 {
//    int z() {
//        X x = new X() {
//            @Override
//            public int go() {
//                return 1;
//            }
//        };
//        return x.go();
//    }
//}
//```
//
//is perfect.
//
//---
//
//## So, what should your tool do differently?
//
//1. **Match the caller’s package.**
//   If the unresolved type is referenced *unqualified* inside `package fixtures.nested1;`, generate the stub in **that same package**.
//
//2. **Preserve member access (`Outer.Inner`).**
//   If the unresolved type appears as `Outer.Inner`, generate **one** top-level `Outer` with **one** member `Inner`, not two separate top-level types or a different package.
//
//3. **Prefer interface for anonymous-with-override.**
//   If you see `new X() { public int go() { ... } }` and there’s no existing `X`, it’s a strong hint you should emit an **interface** `X` with method `int go()` in that package.
//
//---
//
//## Corrected versions (what your tool should have emitted)
//
//**For nested1:**
//
//```java
//package fixtures.nested1;
//
//public class Outer {
//    public static class Inner {
//        public void set(int x) {
//        }
//    }
//}
//```
//
//**For nested2:**
//
//```java
//package fixtures.nested2;
//
//public interface X {
//    int go();
//}
//```
//
//If your tool produces *that*, your tests should pass.