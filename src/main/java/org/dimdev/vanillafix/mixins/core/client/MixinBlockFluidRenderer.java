package org.dimdev.vanillafix.mixins.core.client;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.dimdev.vanillafix.IPatchedCompiledChunk;
import org.dimdev.vanillafix.TemporaryStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Set;

@Mixin(BlockFluidRenderer.class)
public class MixinBlockFluidRenderer {
    /**
     * @reason Adds liquid textures to the set of visible textures in the compiled chunk. Note
     * that this is necessary only for liquid textures, since Forge liquids are rendered by the
     * normal block rendering code.
     */
    @ModifyVariable(method = "renderFluid", at = @At(value = "CONSTANT", args = "floatValue=0.001", ordinal = 1), ordinal = 0)
    private TextureAtlasSprite afterTextureDetermined(TextureAtlasSprite texture) {
        CompiledChunk compiledChunk = TemporaryStorage.currentCompiledChunk.get(Thread.currentThread().getId());
        Set<TextureAtlasSprite> visibleTextures;
        if (compiledChunk != null) {
            visibleTextures = ((IPatchedCompiledChunk) compiledChunk).getVisibleTextures();
        } else {
            // Called from non-chunk render thread. Unfortunately, the best we can do
            // is assume it's only going to be used once:
            visibleTextures = TemporaryStorage.texturesUsed;
        }

        visibleTextures.add(texture);
        return texture;
    }
}
