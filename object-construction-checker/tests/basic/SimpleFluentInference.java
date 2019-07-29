import org.checkerframework.checker.objectconstruction.qual.*;
import org.checkerframework.checker.returnsrcvr.qual.*;

/* Simple inference of a fluent builder */
class SimpleFluentInference {
    SimpleFluentInference build(@CalledMethods({"a", "b"}) SimpleFluentInference this) { return this; }
    SimpleFluentInference weakbuild(@CalledMethods({"a"}) SimpleFluentInference this) { return this; }

    @This SimpleFluentInference a() { return this; }

    @This SimpleFluentInference b() { return this; }

    // intentionally does not have an @This annotation
    SimpleFluentInference c() { return new SimpleFluentInference(); }

    static void doStuffCorrect() {
        SimpleFluentInference s = new SimpleFluentInference()
                .a()
                .b()
                .build();
    }

    static void doStuffWrong() {
        SimpleFluentInference s = new SimpleFluentInference()
                .a()
                // :: error: method.invocation.invalid
                .build();
    }

    static void doStuffRightWeak() {
        SimpleFluentInference s = new SimpleFluentInference()
                .a()
                .weakbuild();
    }

    static void noReturnsReceiverAnno() {
        SimpleFluentInference s = new SimpleFluentInference()
                .a()
                .b()
                .c()
                // :: error: method.invocation.invalid
                .build();
    }

    static void fluentLoop() {
        SimpleFluentInference s = new SimpleFluentInference().a();
        int i = 10;
        while (i > 0) {
            // :: error: method.invocation.invalid
            s.b().build();
            i--;
            s = new SimpleFluentInference();
        }
    }
}