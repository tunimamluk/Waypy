package dev.waypy.config;

/** When the name/distance label under a waypoint logo is shown. */
public enum LabelMode {
	/** Every marker has a label (decluttered: on overlap the nearer waypoint wins). */
	ALWAYS("always"),
	/** Only the marker nearest the crosshair (within the focus radius) has a label. */
	LOOKING_AT("looking_at"),
	/** No labels at all. */
	OFF("off");

	private final String key;

	LabelMode(String key) {
		this.key = key;
	}

	/** Translation key of the value, e.g. {@code waypy.settings.labels.always}. */
	public String translationKey() {
		return "waypy.settings.labels." + key;
	}
}
