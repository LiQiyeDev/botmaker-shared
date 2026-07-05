package com.botmaker.shared.capture;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Cross-platform native window plumbing shared by the SDK (runtime) and Studio (editor).
 *
 * <p>Per-window capture ({@link #captureWindow}) returns {@code null} when it cannot produce a usable
 * frame (e.g. native Wayland, invalid geometry); callers supply their own full-desktop fallback. There
 * is deliberately no {@code captureDesktop()} here — the full-desktop capture engine lives in each
 * consumer (the SDK's {@code CaptureBackend}, the Studio's Robot/CLI grab).
 */
public interface NativeController {
	GenericWindow getForegroundWindow();
	List<GenericWindow> getChildWindows(GenericWindow parent);
	List<GenericWindow> getAllWindows();

	/** Capture just this window's pixels, or {@code null} if a usable frame can't be produced. */
	BufferedImage captureWindow(GenericWindow window);

	void postLeftClick(GenericWindow window, int relativeX, int relativeY);
	void postLeftClickScreen(int xAbs, int yAbs);

	// --- Window management ---
	void focusWindow(GenericWindow window);
	void moveWindow(GenericWindow window, int x, int y);
	void resizeWindow(GenericWindow window, int width, int height);

	// --- Input synthesis ---
	// keyDown/keyUp take a per-OS native key code (X keysym on Linux, virtual-key code on Windows);
	// callers resolve it from api.interaction.Key so the public API stays platform-neutral.
	void keyDown(int nativeKeyCode);
	void keyUp(int nativeKeyCode);
	void typeText(String text);
	void mouseMove(int xAbs, int yAbs);
	void mouseButton(int button, boolean press); // 1=left, 2=middle, 3=right
	void scroll(int amount);                      // + = up/away, - = down/toward
}
