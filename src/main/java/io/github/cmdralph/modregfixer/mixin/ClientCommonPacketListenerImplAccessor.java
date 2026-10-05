package io.github.cmdralph.modregfixer.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the server entry and brand of a connection, for diagnostics. */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {
	@Accessor("serverData")
	@Nullable ServerData modregfixer$getServerData();

	@Accessor("serverBrand")
	@Nullable String modregfixer$getServerBrand();
}
