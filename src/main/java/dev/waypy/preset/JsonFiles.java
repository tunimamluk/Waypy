package dev.waypy.preset;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import dev.waypy.waypoint.WaypointManager;

/** File and field helpers shared by the global JSON stores ({@link PresetManager}, {@link LayoutManager}). */
final class JsonFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");

	private JsonFiles() {
	}

	/** Writes {@code content} to {@code file} through a temporary sibling, moved over it atomically where possible. */
	static void writeAtomically(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			out.write(content);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Moves a corrupt file aside to {@code <name>.corrupt-<millis>}, so the next save can't destroy it. */
	static void backupCorrupt(Path file) {
		try {
			Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
			Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("[Waypy] Moved corrupt file {} to {}", file, backup);
		} catch (IOException e) {
			LOGGER.error("[Waypy] Could not back up corrupt file {}", file, e);
		}
	}

	/** {@code #RRGGBB}. */
	static String colorString(int color) {
		return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
	}

	/** {@code "#RRGGBB"}, {@code "RRGGBB"} or a number; the first palette colour (white dye) if missing or invalid. */
	static int parseColor(JsonObject o, String key) {
		try {
			JsonElement el = o.get(key);
			if (el == null || el.isJsonNull()) return WaypointManager.paletteColor(0);
			if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt() & 0xFFFFFF;
			String s = el.getAsString().trim();
			if (s.startsWith("#")) s = s.substring(1);
			return Integer.parseUnsignedInt(s, 16) & 0xFFFFFF;
		} catch (RuntimeException e) {
			return WaypointManager.paletteColor(0);
		}
	}

	static <E extends Enum<E>> E parseEnum(Class<E> type, JsonObject o, String key, E fallback) {
		if (!o.has(key)) return fallback;
		try {
			return Enum.valueOf(type, o.get(key).getAsString().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** An identifier field; {@code fallback} if missing or invalid. */
	static Identifier parseIdentifier(JsonObject o, String key, Identifier fallback) {
		try {
			if (!o.has(key)) return fallback;
			Identifier id = Identifier.tryParse(o.get(key).getAsString());
			return id != null ? id : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	static boolean parseBoolean(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** A 0..1 opacity; {@code fallback} if missing or not a finite number. */
	static float parseOpacity(JsonObject o, String key, float fallback) {
		try {
			if (!o.has(key)) return fallback;
			float value = o.get(key).getAsFloat();
			return Float.isFinite(value) ? Math.clamp(value, 0.0f, 1.0f) : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	static String parseString(JsonObject o, String key, String fallback) {
		try {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
