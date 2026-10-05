package io.github.cmdralph.modregfixer.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import io.github.cmdralph.modregfixer.ModRegFixerClient;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Configuration stored in {@code config/modregfixer.properties}.
 *
 * <p>Every option can also be forced from the JVM command line, which is handy for testing:
 * {@code -Dmodregfixer.debug=true}, {@code -Dmodregfixer.enabled=false},
 * {@code -Dmodregfixer.hideUnsupportedCreativeItems=false}. A system property always wins over the
 * file.
 */
public final class ModRegFixerConfig {
	private static final String FILE_NAME = "modregfixer.properties";

	private static final String KEY_ENABLED = "enabled";
	private static final String KEY_DEBUG = "debug";
	private static final String KEY_HIDE_CREATIVE = "hideUnsupportedCreativeItems";

	private static volatile ModRegFixerConfig instance = new ModRegFixerConfig(true, false, true);

	private final boolean enabled;
	private final boolean debug;
	private final boolean hideUnsupportedCreativeItems;

	private ModRegFixerConfig(boolean enabled, boolean debug, boolean hideUnsupportedCreativeItems) {
		this.enabled = enabled;
		this.debug = debug;
		this.hideUnsupportedCreativeItems = hideUnsupportedCreativeItems;
	}

	public static ModRegFixerConfig get() {
		return instance;
	}

	/** Master switch. When {@code false} the mod changes nothing at all. */
	public boolean enabled() {
		return enabled;
	}

	/** Log every filtered component and the full per-registry server report at INFO level. */
	public boolean debug() {
		return debug;
	}

	/** On non-Fabric servers, hide items from the creative menu whose mod the server does not have. */
	public boolean hideUnsupportedCreativeItems() {
		return hideUnsupportedCreativeItems;
	}

	public ModRegFixerConfig withDebug(boolean value) {
		return new ModRegFixerConfig(enabled, value, hideUnsupportedCreativeItems);
	}

	/** Loads the config file, creating it with defaults if it does not exist yet. */
	public static void load() {
		Path path = path();
		Properties properties = new Properties();

		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				ModRegFixerClient.LOGGER.warn("[ModRegFixer] Could not read {}, using defaults", path, e);
			}
		}

		ModRegFixerConfig loaded = new ModRegFixerConfig(
				read(properties, KEY_ENABLED, true),
				read(properties, KEY_DEBUG, false),
				read(properties, KEY_HIDE_CREATIVE, true));
		instance = loaded;

		if (!Files.exists(path)) {
			loaded.save();
		}
	}

	/** Replaces the active config and writes it to disk. */
	public static void set(ModRegFixerConfig config) {
		instance = config;
		config.save();
	}

	private void save() {
		Path path = path();

		try {
			Files.createDirectories(path.getParent());

			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				writer.write("""
						# ModRegFixer configuration
						#
						# enabled: master switch. false = the mod does nothing at all.
						# debug: log every filtered component and the full server registry report.
						#        Same as launching with -Dmodregfixer.debug=true
						# hideUnsupportedCreativeItems: on servers without Fabric registry sync, hide
						#        creative-menu items from mods the server shows no trace of.
						""");
				writer.write(KEY_ENABLED + "=" + enabled + "\n");
				writer.write(KEY_DEBUG + "=" + debug + "\n");
				writer.write(KEY_HIDE_CREATIVE + "=" + hideUnsupportedCreativeItems + "\n");
			}
		} catch (IOException e) {
			ModRegFixerClient.LOGGER.warn("[ModRegFixer] Could not write {}", path, e);
		}
	}

	private static boolean read(Properties properties, String key, boolean fallback) {
		String override = System.getProperty("modregfixer." + key);

		if (override != null) {
			return Boolean.parseBoolean(override.trim());
		}

		String value = properties.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
