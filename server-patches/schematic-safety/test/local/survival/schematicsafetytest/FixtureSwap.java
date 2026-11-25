package local.survival.schematicsafetytest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
/** Test-only helper; never packaged in the production add-on. */
public final class FixtureSwap {
    public static void restore() throws Exception {
        if (!Files.isRegularFile(Path.of(".schematic-safety-selftest"))) throw new IllegalStateException("Not a test server");
        Files.copy(Path.of("schematics/uploaded/selftest/valid.nbt"), Path.of("schematics/uploaded/selftest/repair.nbt"), StandardCopyOption.REPLACE_EXISTING);
    }
}
