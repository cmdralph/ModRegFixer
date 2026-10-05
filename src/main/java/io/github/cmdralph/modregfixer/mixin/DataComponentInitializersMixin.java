package io.github.cmdralph.modregfixer.mixin;

import java.util.Map;
import java.util.function.Function;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.cmdralph.modregfixer.CompatibilityManager;
import net.minecraft.core.component.DataComponentInitializers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Diagnostics only: notes which element is about to be initialized so that, if a failure cannot be
 * filtered, the error message can name the item and its mod.
 *
 * <pre>{@code
 * for (InitializerEntry<?> initializer : this.initializers) {
 *     DataComponentMap.Builder builder = results.computeIfAbsent(initializer.key, k -> DataComponentMap.builder());   // ← observed
 *     initializer.run(builder, context);
 * }
 * }</pre>
 *
 * The call itself is passed through unchanged.
 */
@Mixin(DataComponentInitializers.class)
public abstract class DataComponentInitializersMixin {
	@SuppressWarnings({"rawtypes", "unchecked"})
	@WrapOperation(method = "runInitializers", at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
	private Object modregfixer$noteElement(Map map, Object key, Function mappingFunction, Operation<Object> original) {
		CompatibilityManager.noteCurrentElement(key);
		return original.call(map, key, mappingFunction);
	}
}
