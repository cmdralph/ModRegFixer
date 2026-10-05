package io.github.cmdralph.modregfixer.compat;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;

/**
 * A registry element, tag or whole registry that client content asked for but that the
 * server's registries do not contain.
 *
 * @param kind     what kind of lookup failed
 * @param registry the registry that was queried (for {@link Kind#REGISTRY}, the missing registry itself)
 * @param id       the missing element or tag id, or {@code null} for {@link Kind#REGISTRY}
 */
public record MissingReference(Kind kind, Identifier registry, @Nullable Identifier id) {
	public enum Kind {
		/** The whole registry is absent from the server's registry set. */
		REGISTRY,
		/** The registry exists but does not contain the requested element. */
		ELEMENT,
		/** The registry exists but does not contain the requested tag. */
		TAG
	}

	public static MissingReference element(ResourceKey<?> key) {
		return new MissingReference(Kind.ELEMENT, key.registry(), key.identifier());
	}

	public static MissingReference tag(TagKey<?> tag) {
		return new MissingReference(Kind.TAG, tag.registry().identifier(), tag.location());
	}

	public static MissingReference registry(ResourceKey<? extends Registry<?>> registryKey) {
		return new MissingReference(Kind.REGISTRY, registryKey.identifier(), null);
	}

	/**
	 * The namespace that "owns" the missing content. For an element or tag this is the
	 * namespace of its id (e.g. {@code biomesoplenty} for {@code biomesoplenty:glowworm_silk});
	 * for a missing registry it is the registry's namespace.
	 */
	public String namespace() {
		return id != null ? id.getNamespace() : registry.getNamespace();
	}

	/** Human-readable form, matching vanilla's {@code ResourceKey[registry / id]} wording. */
	public String describe() {
		return switch (kind) {
			case REGISTRY -> "registry " + registry;
			case ELEMENT -> registry + " / " + id;
			case TAG -> registry + " / #" + id;
		};
	}

	@Override
	public String toString() {
		return describe();
	}
}
