package com.botmaker.shared.ipc;

/**
 * A single geometry-only telemetry frame sent from a running bot to the Studio's window-preview panel.
 * Deliberately carries <em>no image bytes</em> — the Studio captures the window frame itself; these events
 * only describe <em>where</em> the vision/interaction functions acted, so frames stay tiny.
 *
 * <p>Lives in {@code botmaker-shared} because it is the single module both the SDK (emitter) and the Studio
 * (consumer) depend on, so the wire vocabulary has one definition. Encoded/decoded by {@link TelemetryFrame}.
 */
public sealed interface TelemetryEvent
        permits TelemetryEvent.Match, TelemetryEvent.Click, TelemetryEvent.Region {

    /** The surface an event refers to, so the Studio can capture the right window/screen. */
    record Target(String title, int x, int y, int width, int height) {}

    /** An axis-aligned rectangle in absolute (virtual-screen) coordinates. */
    record Rect(int x, int y, int width, int height) {}

    /** The surface this event acted on. Never null. */
    Target target();

    /**
     * The 1-based line in the bot's source that triggered this event, or {@code -1} when unknown. Lets the
     * Studio highlight the running block live during a plain run (debug/trace already highlights via JDI).
     */
    int line();

    /**
     * A template match attempt. {@code rect} is the matched bounds (null when {@code !found});
     * {@code region} is the search sub-rectangle (null for a whole-surface search).
     */
    record Match(Target target, Rect region, Rect rect, double confidence, boolean found, int line)
            implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Match(Target target, Rect region, Rect rect, double confidence, boolean found) {
            this(target, region, rect, confidence, found, -1);
        }
    }

    /** A click landing at ({@code x},{@code y}) absolute. {@code button}: 1=left, 2=middle, 3=right. */
    record Click(Target target, int x, int y, int button, int line) implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Click(Target target, int x, int y, int button) {
            this(target, x, y, button, -1);
        }
    }

    /** A standalone search-region highlight (a search scoped to a sub-rectangle of the surface). */
    record Region(Target target, Rect rect, int line) implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Region(Target target, Rect rect) {
            this(target, rect, -1);
        }
    }
}
