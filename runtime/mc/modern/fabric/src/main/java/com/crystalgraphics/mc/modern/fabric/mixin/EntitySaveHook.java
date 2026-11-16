package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.world.EntitySavesModern;
import com.crystalgraphics.mc.modern.platform.world.SavedEntity;
import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.6 {
/*import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
*///?} else {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

// What the engine saves with an entity (EntitySavesModern), where Fabric has no persistent data of its own: a compound
// kept on the entity, filled as the save starts (CgEntitySaves.saving: a change costs a flag until then), its key
// written into the entity's save and read back as it loads. No refmap, so each method is
// named in both namespaces: Mojang (the dev run, and 26.1+) and intermediary.
@Mixin(value = Entity.class, remap = false)
public abstract class EntitySaveHook implements SavedEntity {

    @Unique
    private CompoundTag crystalgraphics$saved;

    @Override
    public CompoundTag crystalgraphics$saved() {
        if (crystalgraphics$saved == null) crystalgraphics$saved = new CompoundTag();
        return crystalgraphics$saved;
    }

    //? if >=1.21.6 {
    /*@Inject(method = {"saveWithoutId", "method_5647"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(ValueOutput output, CallbackInfo ci) {
        CgEntitySaves.saving(this);
    }

    @Inject(method = {"saveWithoutId", "method_5647"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$save(ValueOutput output, CallbackInfo ci) {
        Tag ours = crystalgraphics$saved == null ? null : crystalgraphics$saved.get(EntitySavesModern.KEY);
        if (ours instanceof CompoundTag) output.store(EntitySavesModern.KEY, CompoundTag.CODEC, (CompoundTag) ours);
    }

    @Inject(method = {"load", "method_5651"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$load(ValueInput input, CallbackInfo ci) {
        input.read(EntitySavesModern.KEY, CompoundTag.CODEC).ifPresent(ours -> crystalgraphics$saved().put(EntitySavesModern.KEY, ours));
    }
    *///?} else {
    @Inject(method = {"saveWithoutId", "method_5647"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        CgEntitySaves.saving(this);
    }

    @Inject(method = {"saveWithoutId", "method_5647"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        Tag ours = crystalgraphics$saved == null ? null : crystalgraphics$saved.get(EntitySavesModern.KEY);
        if (ours instanceof CompoundTag) tag.put(EntitySavesModern.KEY, ours);
    }

    @Inject(method = {"load", "method_5651"}, at = @At("RETURN"), require = 1)
    private void crystalgraphics$load(CompoundTag tag, CallbackInfo ci) {
        Tag ours = tag.get(EntitySavesModern.KEY);
        if (ours instanceof CompoundTag) crystalgraphics$saved().put(EntitySavesModern.KEY, ours);
    }
    //?}
}
