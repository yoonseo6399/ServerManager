package local.survival.schematicsafety;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Confines corrupt NBT input to the schematic load boundary; never catches JVM Errors. */
public final class SchematicFailureGuard {
    private static final System.Logger LOG = System.getLogger("Survival Schematic Safety");
    private static final Map<String, Long> reported = new LinkedHashMap<>();
    private SchematicFailureGuard() {}

    public static boolean isReadFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < 32 && visited.add(failure); depth++, failure = failure.getCause()) {
            if (failure instanceof IOException) return true;
            String name = failure.getClass().getName();
            if (name.equals("net.minecraft.nbt.NbtAccounterException") || name.equals("net.minecraft.nbt.NbtFormatException")) return true;
        }
        return false;
    }

    public static <T> T read(String owner, String filename, Supplier<T> original, Supplier<T> empty) {
        try {
            return original.get();
        } catch (RuntimeException failure) {
            if (!isReadFailure(failure)) throw failure;
            String label = clean(owner) + "/" + clean(filename);
            boolean log;
            synchronized (reported) {
                long now = System.nanoTime();
                Long previous = reported.get(label);
                log = previous == null || now - previous >= 60_000_000_000L;
                if (log) {
                    if (reported.size() >= 1024) reported.remove(reported.keySet().iterator().next());
                    reported.put(label, now);
                }
            }
            if (log) LOG.log(System.Logger.Level.WARNING,
                "[Survival Schematic Safety] Rejected unreadable schematic " + label
                + "; returned an empty template. Original file preserved; re-upload an intact copy.", failure);
            return empty.get();
        }
    }

    private static String clean(String value) {
        if (value == null) return "<unknown>";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(value.length(), 200); i++) {
            char c = value.charAt(i); out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}
