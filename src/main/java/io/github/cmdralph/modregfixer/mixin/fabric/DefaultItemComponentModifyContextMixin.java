package io.github.cmdralph.modregfixer.mixin.fabric;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.cmdralph.modregfixer.compat.FabricItemApiGuards;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Guards listeners of Fabric API's {@code DefaultItemComponentEvents.MODIFY} (see
 * {@link FabricItemApiGuards}). Targets Fabric API's implementation class
 * {@code DefaultItemComponentImpl.ModifyContextImpl} (unchanged across Fabric API for 26.1–26.3):
 *
 * <pre>{@code
 * for (Item item : BuiltInRegistries.ITEM) {
 *     if (itemPredicate.test(item)) {
 *         DataComponentMap.Builder builder = DataComponentMap.builder().addAll(item.components());
 *         builderConsumer.modify(builder, registryLookup, item);        // ← wrapped
 *         item.builtInRegistryHolder().bindComponents(builder.build());
 *     }
 * }
 * }</pre>
 *
 * This lives in its own optional mixin config ({@code "required": false}, {@code require = 0}) and
 * is {@link Pseudo}: if a future Fabric API changes this internal class, the hook silently does not
 * apply and everything else keeps working.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.item.DefaultItemComponentImpl$ModifyContextImpl")
public abstract class DefaultItemComponentModifyContextMixin {
	@WrapOperation(
			method = "modify(Ljava/util/function/Predicate;Lnet/fabricmc/fabric/api/item/v1/DefaultItemComponentEvents$ModifyConsumer;)V",
			at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/api/item/v1/DefaultItemComponentEvents$ModifyConsumer;modify(Lnet/minecraft/core/component/DataComponentMap$Builder;Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/item/Item;)V"),
			require = 0)
	private void modregfixer$guardListener(
			DefaultItemComponentEvents.ModifyConsumer consumer,
			DataComponentMap.Builder builder,
			HolderLookup.Provider registries,
			Item item,
			Operation<Void> original) {
		if (FabricItemApiGuards.shouldSkip(consumer, registries, item)) {
			return;
		}

		original.call(consumer, builder, registries, item);
	}
}
