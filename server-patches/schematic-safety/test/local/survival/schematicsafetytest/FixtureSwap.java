package local.survival.schematicsafetytest;

import com.google.gson.Gson;
import com.simibubi.create.AllDataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.content.schematics.SchematicPrinter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Fixed-path test harness only; never packaged in the production add-on. */
public final class FixtureSwap {
    private static ItemStack stack(String filename) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create", "schematic")));
        stack.set(AllDataComponents.SCHEMATIC_OWNER, "selftest");
        stack.set(AllDataComponents.SCHEMATIC_FILE, filename);
        stack.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        stack.set(AllDataComponents.SCHEMATIC_ANCHOR, new BlockPos(0, 80, 0));
        return stack;
    }
    private static boolean empty(StructureTemplate value) {
        return value.getSize().getX() == 0 && value.getSize().getY() == 0 && value.getSize().getZ() == 0;
    }
    private static void check(List<Map<String,Object>> checks, String name, boolean pass, String detail) {
        checks.add(Map.of("name", name, "pass", pass, "detail", detail));
        if (!pass) throw new AssertionError(name + ": " + detail);
    }
    public static String run(Level level, boolean patched) {
        Map<String,Object> report = new LinkedHashMap<>();List<Map<String,Object>> checks = new ArrayList<>();
        report.put("patched", patched);report.put("checks", checks);report.put("success", false);
        try {
            if (!Files.isRegularFile(Path.of(".schematic-safety-selftest"))) throw new IllegalStateException("Not a test server");
            StructureTemplate valid = SchematicItem.loadSchematic(level, stack("valid.nbt"));
            check(checks, "valid schematic remains one stone block", valid.getSize().getX() == 1 && valid.getSize().getY() == 1 && valid.getSize().getZ() == 1, valid.getSize().toString());
            for (String file : List.of("truncated-real.nbt", "truncated-nbt.nbt", "repair.nbt")) {
                boolean threw = false;String detail = "empty template";StructureTemplate result = null;
                try {result = SchematicItem.loadSchematic(level, stack(file));}
                catch (RuntimeException failure) {threw = true;detail = failure.toString();}
                check(checks, file + " is contained only when patched", patched ? !threw && empty(result) : threw, detail);
            }
            check(checks, "missing file keeps existing empty fallback", empty(SchematicItem.loadSchematic(level, stack("missing.nbt"))), "");
            Files.copy(Path.of("schematics/uploaded/selftest/valid.nbt"), Path.of("schematics/uploaded/selftest/repair.nbt"), StandardCopyOption.REPLACE_EXISTING);
            check(checks, "re-uploaded file retries without permanent error cache", SchematicItem.loadSchematic(level, stack("repair.nbt")).getSize().getX() == 1, "");
            boolean printerThrew = false;String detail = "no exception";
            try {new SchematicPrinter().loadSchematic(stack("truncated-real.nbt"), level, false);}
            catch (RuntimeException failure) {printerThrew = true;detail = failure.toString();}
            check(checks, "printer survives bad NBT only when patched", patched ? !printerThrew : printerThrew, detail);
            SchematicPrinter printer = new SchematicPrinter();printer.loadSchematic(stack("valid.nbt"), level, false);
            check(checks, "valid printer stays loaded without errors", printer.isLoaded() && !printer.isErrored(), "");
            // Exercise the exact cannon boundary from the production crash in this test world only.
            BlockPos position = new BlockPos(0, 80, 0);
            level.setBlockAndUpdate(position, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "schematicannon")).defaultBlockState());
            try {
                Object cannon = level.getBlockEntity(position);
                Class<?> type = Class.forName("com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity");
                var method = type.getDeclaredMethod("initializePrinter", ItemStack.class);method.setAccessible(true);
                boolean cannonThrew = false;String cannonDetail = "no exception";
                try {method.invoke(cannon, stack("truncated-real.nbt"));}
                catch (java.lang.reflect.InvocationTargetException failure) {cannonThrew = true;cannonDetail = failure.getCause().toString();}
                check(checks, "schematicannon initialization rejects bad NBT without crashing when patched", patched ? !cannonThrew : cannonThrew, cannonDetail);
            } finally {level.removeBlock(position, false);}
            report.put("success", true);
        } catch (Throwable failure) {report.put("error", failure.toString());}
        return new Gson().toJson(report);
    }
}
