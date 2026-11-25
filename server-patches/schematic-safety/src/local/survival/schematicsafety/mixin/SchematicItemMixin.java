package local.survival.schematicsafety.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.AllDataComponents;
import local.survival.schematicsafety.SchematicFailureGuard;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;

/** Wraps the whole method so Railways and other read-site wrappers remain inside the boundary. */
@Mixin(targets = "com.simibubi.create.content.schematics.SchematicItem", remap = false)
public abstract class SchematicItemMixin {
    @WrapMethod(method = "loadSchematic(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;",
        remap = false, require = 1)
    private static StructureTemplate survivalSchematicSafety$read(Level level, ItemStack stack, Operation<StructureTemplate> original) {
        return SchematicFailureGuard.read(stack.get(AllDataComponents.SCHEMATIC_OWNER), stack.get(AllDataComponents.SCHEMATIC_FILE),
            () -> original.call(level, stack), StructureTemplate::new);
    }
}
