package io.github.cmdralph.modregfixer.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jspecify.annotations.Nullable;

/**
 * A read-only view of a {@link HolderLookup.Provider} that remembers every lookup which came back
 * empty.
 *
 * <p>It never fabricates anything: every holder, holder set and registry it returns is the
 * server's real object, obtained from the wrapped provider. It is used only to <em>probe</em>
 * an initializer (see {@link ComponentGuards}); the value that actually ends up on an item is
 * always produced by vanilla code against the real, unwrapped provider.
 *
 * <p>All vanilla {@code getOrThrow} variants are interface default methods that route through
 * {@link #lookup} and {@link HolderLookup.RegistryLookup#get}, so overriding those two points is
 * enough to observe every missing element, tag and registry.
 */
public final class RecordingLookupProvider implements HolderLookup.Provider {
	private final HolderLookup.Provider delegate;
	private final List<MissingReference> missing = new ArrayList<>(2);

	public RecordingLookupProvider(HolderLookup.Provider delegate) {
		this.delegate = delegate;
	}

	@Override
	public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
		return delegate.listRegistryKeys();
	}

	@Override
	public <T> Optional<? extends HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
		Optional<? extends HolderLookup.RegistryLookup<T>> found = delegate.lookup(key);

		if (found.isEmpty()) {
			missing.add(MissingReference.registry(key));
			return Optional.empty();
		}

		return Optional.of(new RecordingRegistryLookup<>(found.get(), this));
	}

	/** The first lookup that came back empty, or {@code null} if every lookup succeeded. */
	public @Nullable MissingReference firstMissing() {
		return missing.isEmpty() ? null : missing.getFirst();
	}

	public List<MissingReference> missing() {
		return List.copyOf(missing);
	}

	void record(MissingReference reference) {
		missing.add(reference);
	}

	/**
	 * Delegating registry view. Everything except element/tag lookup is inherited from
	 * {@link HolderLookup.RegistryLookup.Delegate}, which forwards to the real registry.
	 */
	private static final class RecordingRegistryLookup<T> implements HolderLookup.RegistryLookup.Delegate<T> {
		private final HolderLookup.RegistryLookup<T> parent;
		private final RecordingLookupProvider owner;

		private RecordingRegistryLookup(HolderLookup.RegistryLookup<T> parent, RecordingLookupProvider owner) {
			this.parent = parent;
			this.owner = owner;
		}

		@Override
		public HolderLookup.RegistryLookup<T> parent() {
			return parent;
		}

		@Override
		public Optional<Holder.Reference<T>> get(ResourceKey<T> id) {
			Optional<Holder.Reference<T>> result = parent.get(id);

			if (result.isEmpty()) {
				owner.record(MissingReference.element(id));
			}

			return result;
		}

		@Override
		public Optional<HolderSet.Named<T>> get(TagKey<T> id) {
			Optional<HolderSet.Named<T>> result = parent.get(id);

			if (result.isEmpty()) {
				owner.record(MissingReference.tag(id));
			}

			return result;
		}

		/**
		 * Holders keep their real owner, so serialization checks must be answered by the real
		 * registry. Declared explicitly because 26.1's {@code Delegate} does not forward it.
		 */
		@Override
		public boolean canSerialize(HolderOwner<T> holderOwner) {
			return parent.canSerialize(holderOwner);
		}
	}
}
