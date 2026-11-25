package local.survival.schematicsafety;

import java.io.EOFException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchematicFailureGuardTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        AtomicInteger calls = new AtomicInteger(); Object expected = new Object();
        check(SchematicFailureGuard.read("ok", "ok.nbt", () -> {calls.incrementAndGet(); return expected;}, () -> {throw new AssertionError();}) == expected);
        check(calls.get() == 1);
        RuntimeException corrupt = new RuntimeException("Loading NBT", new EOFException("Unexpected end of ZLIB input stream"));
        check(SchematicFailureGuard.isReadFailure(corrupt));
        check(SchematicFailureGuard.read("bad", "bad.nbt", () -> {throw corrupt;}, () -> expected) == expected);
        check(SchematicFailureGuard.read("io", "io.nbt", () -> {throw new UncheckedIOException(new EOFException());}, () -> expected) == expected);
        for (RuntimeException other : new RuntimeException[] {new IllegalArgumentException("unrelated"), new NullPointerException("mod bug")}) {
            try {SchematicFailureGuard.read("other", "other.nbt", () -> {throw other;}, () -> expected); throw new AssertionError();}
            catch (RuntimeException caught) {check(caught == other);}
        }
        OutOfMemoryError error = new OutOfMemoryError("test only");
        try {SchematicFailureGuard.read("oom", "oom.nbt", () -> {throw error;}, () -> expected); throw new AssertionError();}
        catch (OutOfMemoryError caught) {check(caught == error);}
        RuntimeException a = new RuntimeException(); RuntimeException b = new RuntimeException();a.initCause(b); b.initCause(a);
        check(!SchematicFailureGuard.isReadFailure(a));
        check(SchematicFailureGuard.read("bad", "bad.nbt", () -> expected, () -> null) == expected);
        System.out.println("PASS: valid data, wrapped EOF, unchecked IO, unrelated exceptions, JVM Error, cyclic causes, repaired input retry");
    }
}
