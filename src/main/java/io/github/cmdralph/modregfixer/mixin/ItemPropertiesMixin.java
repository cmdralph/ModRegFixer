package io.github.cmdralph.modregfixer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.cmdralph.modregfixer.compat.ComponentGuards;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps the two {@code Item.Properties} methods that create components which depend on the
 * server's registries:
 *
 * <pre>{@code
 * // 26.x vanilla
 * public <T> Properties delayedHolderComponent(DataComponentType<Holder<T>> type, ResourceKey<T> valueKey) {
 *     this.componentInitializer = this.componentInitializer.andThen(
 *             (components, context, key) -> components.set(type, context.getOrThrow(valueKey)));
 *     return this;
 * }
 *
 * public <T> Properties delayedComponent(DataComponentType<T> type, SingleComponentInitializer<T> initializer) {
 *     this.componentInitializer = this.componentInitializer.andThen(initializer.asInitializer(type));
 *     return this;
 * }
 * }</pre>
 *
 * {@code trimMaterial(..)}, {@code jukeboxPlayable(..)}, {@code fireResistant()},
 * {@code potPattern(..)}, {@code spear(..)}, {@code axe/hoe/shovel(..)} and
 * {@code loweredMobVisibility(..)} all go through these two methods, as does any mod code calling
 * them directly (this is how Biomes O' Plenty defines {@code glowworm_silk}, {@code rose_quartz_chunk}
 * and {@code music_disc_wanderer}).
 *
 * <p>The argument to {@code andThen} is exactly the new link, so wrapping that argument replaces
 * the link with a guarded version and nothing else: the chain, the field and the return value
 * stay vanilla's. {@code @WrapOperation} composes with other mods wrapping the same call.
 */
@Mixin(Item.Properties.class)
public abstract class ItemPropertiesMixin {
	private static final String AND_THEN = "Lnet/minecraft/core/component/DataComponentInitializers$Initializer;andThen(Lnet/minecraft/core/component/DataComponentInitializers$Initializer;)Lnet/minecraft/core/component/DataComponentInitializers$Initializer;";

	@WrapOperation(method = "delayedHolderComponent", at = @At(value = "INVOKE", target = AND_THEN))
	private DataComponentInitializers.Initializer<Item> modregfixer$guardHolderComponent(
			DataComponentInitializers.Initializer<Item> chain,
			DataComponentInitializers.Initializer<Item> link,
			Operation<DataComponentInitializers.Initializer<Item>> original,
			@Local(argsOnly = true) DataComponentType<?> type,
			@Local(argsOnly = true) ResourceKey<?> valueKey) {
		return original.call(chain, ComponentGuards.guardHolderComponent(type, valueKey, link));
	}

	@WrapOperation(method = "delayedComponent", at = @At(value = "INVOKE", target = AND_THEN))
	private DataComponentInitializers.Initializer<Item> modregfixer$guardDelayedComponent(
			DataComponentInitializers.Initializer<Item> chain,
			DataComponentInitializers.Initializer<Item> link,
			Operation<DataComponentInitializers.Initializer<Item>> original,
			@Local(argsOnly = true) DataComponentType<?> type) {
		return original.call(chain, ComponentGuards.guardDelayedComponent(type, link));
	}
}
