package dev.waypy.config;

/** The tabs of the Presets popup (H): which one the Open Presets key opens is the "H Opens" setting. */
public enum PopupTab {
	/** Display presets, applied to the target waypoint. */
	PRESETS("presets"),
	/** Layout presets, stamped onto the world at the target block. */
	LAYOUTS("layouts");

	private final String key;

	PopupTab(String key) {
		this.key = key;
	}

	/** Translation key of the tab's name, e.g. {@code waypy.popup.tab.presets}. */
	public String translationKey() {
		return "waypy.popup.tab." + key;
	}
}
