import org.checkerframework.checker.objectconstruction.qual.*;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.common.returnsreceiver.qual.*;
import org.checkerframework.checker.mustcall.qual.*;
import java.lang.SuppressWarnings;


class HadoopReport1 {

    @MustCall("a")
    static class Foo {
        void a() {
        }
        void c() {
        }
    }

    public static class Merger {
        private Foo[] fooArr;

        private void open(Foo f) {
            fooArr = new Foo[2];
            fooArr[1] = f;
        }
    }

}