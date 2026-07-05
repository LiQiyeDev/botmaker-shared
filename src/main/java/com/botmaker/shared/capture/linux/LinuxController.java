package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux implementation of NativeController using X11
 * Uses Robot for window capture and XTest for mouse clicks
 *
 * Note: This class manages native X11 resources. While it implements AutoCloseable,
 * the display connection is typically kept open for the lifetime of the application.
 * Call close() explicitly if you need to release resources before application exit.
 *
 * <p>{@link #captureWindow} returns {@code null} when it cannot produce a usable frame (X11
 * unavailable, invalid geometry, or a capture error — e.g. native Wayland where Robot returns black).
 * Callers apply their own full-desktop fallback.
 */
public class LinuxController implements NativeController, AutoCloseable {

	private final Pointer display;
	private final boolean x11Available;
	private volatile boolean closed = false;

	public LinuxController() {
		// Try to open X11 display
		Pointer disp = null;
		boolean available = false;

		try {
			disp = X11.INSTANCE.XOpenDisplay(null);
			available = (disp != null);

			if (!available) {
				System.err.println("[Linux] Warning: Could not open X11 display. Falling back to Robot for all operations.");
				System.err.println("[Linux] Make sure DISPLAY environment variable is set and X11 is running.");
			}
		} catch (UnsatisfiedLinkError e) {
			System.err.println("[Linux] Warning: X11 libraries not found. Install libx11-6 and libxtst-6.");
			System.err.println("[Linux] Falling back to Robot for all operations.");
		} catch (Exception e) {
			System.err.println("[Linux] Warning: Error initializing X11: " + e.getMessage());
		}

		this.display = disp;
		this.x11Available = available;
	}

	@Override
	public GenericWindow getForegroundWindow() {
		checkNotClosed();

		if (!x11Available) {
			System.out.println("[Linux] X11 not available, returning mock window.");
			return new GenericWindow(-1, "Mock Linux Window", new Rectangle(0, 0, 800, 600));
		}

		try {
			// Try to get active window via _NET_ACTIVE_WINDOW (EWMH)
			Pointer activeWindow = X11Utils.getActiveWindow(display);

			if (activeWindow == null || Pointer.nativeValue(activeWindow) == 0) {
				// Fallback to XGetInputFocus
				PointerByReference focusReturn = new PointerByReference();
				IntByReference revertToReturn = new IntByReference();
				X11.INSTANCE.XGetInputFocus(display, focusReturn, revertToReturn);
				activeWindow = focusReturn.getValue();
			}

			if (activeWindow == null || Pointer.nativeValue(activeWindow) == 0 || Pointer.nativeValue(activeWindow) == 1) {
				System.out.println("[Linux] No active window found.");
				return null;
			}

			return toGenericWindow(activeWindow);
		} catch (Exception e) {
			System.err.println("[Linux] Error getting foreground window: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public List<GenericWindow> getChildWindows(GenericWindow parent) {
		checkNotClosed();

		List<GenericWindow> result = new ArrayList<>();

		if (!x11Available || parent == null) {
			return result;
		}

		try {
			Pointer parentWindow = (Pointer) parent.getNativeHandle();

			PointerByReference rootReturn = new PointerByReference();
			PointerByReference parentReturn = new PointerByReference();
			PointerByReference childrenReturn = new PointerByReference();
			IntByReference nChildrenReturn = new IntByReference();

			int status = X11.INSTANCE.XQueryTree(
				display, parentWindow,
				rootReturn, parentReturn,
				childrenReturn, nChildrenReturn
			);

			if (status == 0) {
				return result;
			}

			Pointer children = childrenReturn.getValue();
			int nChildren = nChildrenReturn.getValue();

			if (children != null && nChildren > 0) {
				long[] childIds = children.getLongArray(0, nChildren);

				for (long childId : childIds) {
					Pointer childWindow = new Pointer(childId);

					// Only include viewable windows with titles
					if (X11Utils.isWindowViewable(display, childWindow)) {
						String title = X11Utils.getWindowTitle(display, childWindow);
						if (title != null && !title.isEmpty()) {
							GenericWindow gw = toGenericWindow(childWindow);
							if (gw != null) {
								result.add(gw);
							}
						}
					}
				}

				X11.INSTANCE.XFree(children);
			}
		} catch (Exception e) {
			System.err.println("[Linux] Error getting child windows: " + e.getMessage());
			e.printStackTrace();
		}

		return result;
	}

	@Override
	public List<GenericWindow> getAllWindows() {
		checkNotClosed();

		List<GenericWindow> result = new ArrayList<>();

		if (!x11Available) {
			return result;
		}

		try {
			// Get all client windows from window manager
			Pointer[] windows = X11Utils.getClientList(display);

			if (windows.length == 0) {
				// Fallback: enumerate from root window
				Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
				windows = enumerateWindowsRecursive(root);
			}

			for (Pointer window : windows) {
				if (X11Utils.isWindowViewable(display, window) &&
					!X11Utils.hasOverrideRedirect(display, window)) {

					String title = X11Utils.getWindowTitle(display, window);
					if (title != null && !title.isEmpty()) {
						GenericWindow gw = toGenericWindow(window);
						if (gw != null) {
							result.add(gw);
						}
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[Linux] Error getting all windows: " + e.getMessage());
			e.printStackTrace();
		}

		return result;
	}

	/**
	 * Recursively enumerate windows (fallback method)
	 */
	private Pointer[] enumerateWindowsRecursive(Pointer window) {
		List<Pointer> allWindows = new ArrayList<>();

		try {
			PointerByReference rootReturn = new PointerByReference();
			PointerByReference parentReturn = new PointerByReference();
			PointerByReference childrenReturn = new PointerByReference();
			IntByReference nChildrenReturn = new IntByReference();

			int status = X11.INSTANCE.XQueryTree(
				display, window,
				rootReturn, parentReturn,
				childrenReturn, nChildrenReturn
			);

			if (status != 0) {
				Pointer children = childrenReturn.getValue();
				int nChildren = nChildrenReturn.getValue();

				if (children != null && nChildren > 0) {
					long[] childIds = children.getLongArray(0, nChildren);

					for (long childId : childIds) {
						Pointer childWindow = new Pointer(childId);
						allWindows.add(childWindow);

						// Recurse into children
						Pointer[] subWindows = enumerateWindowsRecursive(childWindow);
						for (Pointer sw : subWindows) {
							allWindows.add(sw);
						}
					}

					X11.INSTANCE.XFree(children);
				}
			}
		} catch (Exception e) {
			// Ignore errors during recursive enumeration
		}

		return allWindows.toArray(new Pointer[0]);
	}

	/**
	 * Capture window using Robot (simple and reliable on X11). Returns {@code null} on failure so the
	 * caller can apply its own full-desktop fallback (e.g. the Wayland CLI grab).
	 */
	@Override
	public BufferedImage captureWindow(GenericWindow window) {
		checkNotClosed();

		if (!x11Available || window == null) {
			return null;
		}

		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();

			// Get window geometry
			Rectangle rect = X11Utils.getWindowGeometry(display, x11Window);
			if (rect == null || rect.width <= 0 || rect.height <= 0) {
				System.err.println("[Linux] Invalid window geometry, cannot capture window.");
				return null;
			}

			// Simple and reliable - use Robot for screen capture
			return new Robot().createScreenCapture(rect);

		} catch (Exception e) {
			System.err.println("[Linux] Error capturing window: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Post click using window-relative coordinates
	 * Converts to screen coordinates and uses XTest
	 */
	@Override
	public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
		checkNotClosed();

		if (!x11Available) {
			System.out.println("[Linux] X11 not available, cannot post click.");
			return;
		}

		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();

			// Get window geometry to convert to screen coordinates
			Rectangle rect = X11Utils.getWindowGeometry(display, x11Window);
			if (rect == null) {
				System.err.println("[Linux] Could not get window geometry for click.");
				return;
			}

			// Convert to absolute screen coordinates
			int screenX = rect.x + relativeX;
			int screenY = rect.y + relativeY;

			// Use XTest to simulate click
			postLeftClickScreen(screenX, screenY);
		} catch (Exception e) {
			System.err.println("[Linux] Error posting click: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Post click using absolute screen coordinates
	 * Uses XTest to avoid moving the visible cursor (like Windows PostMessage)
	 */
	@Override
	public void postLeftClickScreen(int xAbs, int yAbs) {
		checkNotClosed();

		if (!x11Available) {
			System.out.println("[Linux] X11 not available, attempting Robot click.");
			try {
				Robot robot = new Robot();
				Point current = MouseInfo.getPointerInfo().getLocation();
				robot.mouseMove(xAbs, yAbs);
				Thread.sleep(10);
				robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
				robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
				Thread.sleep(10);
				robot.mouseMove(current.x, current.y);
			} catch (Exception e) {
				System.err.println("[Linux] Robot click failed: " + e.getMessage());
			}
			return;
		}

		try {
			// XTestFakeMotionEvent warps the real cursor, so remember where it was to restore it after.
			// Restore is X11-only: under native Wayland we run as an XWayland client that can *write*
			// the pointer (warp/click) but cannot *read* the global cursor position (XQueryPointer
			// returns a stale constant when the cursor isn't over our surface — and the bot has none).
			// So on Wayland we skip the restore (cursor stays on target) rather than teleport it to a
			// bogus fixed spot. The Wayland-correct path (RemoteDesktop portal / libei) is roadmapped.
			boolean canRestore = System.getenv("WAYLAND_DISPLAY") == null;
			IntByReference rootX = new IntByReference();
			IntByReference rootY = new IntByReference();
			boolean haveCurrent = false;
			if (canRestore) {
				Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
				haveCurrent = X11.INSTANCE.XQueryPointer(display, root,
						new PointerByReference(), new PointerByReference(),
						rootX, rootY, new IntByReference(), new IntByReference(), new IntByReference());
			}

			// Move pointer to the click position (this moves the visible cursor)
			XTest.INSTANCE.XTestFakeMotionEvent(display, -1, xAbs, yAbs, 0);
			X11.INSTANCE.XFlush(display);

			// Small delay to ensure motion is processed
			Thread.sleep(10);

			// Press left button
			XTest.INSTANCE.XTestFakeButtonEvent(display, XTest.Button1, true, 0);
			X11.INSTANCE.XFlush(display);

			// Small delay between press and release
			Thread.sleep(10);

			// Release left button
			XTest.INSTANCE.XTestFakeButtonEvent(display, XTest.Button1, false, 0);
			X11.INSTANCE.XFlush(display);

			// Restore the pointer to where the user left it, minimizing the visible flicker
			if (haveCurrent) {
				XTest.INSTANCE.XTestFakeMotionEvent(display, -1, rootX.getValue(), rootY.getValue(), 0);
				X11.INSTANCE.XFlush(display);
			}

			System.out.println("[Linux] Click sent to (" + xAbs + ", " + yAbs + ")");
		} catch (Exception e) {
			System.err.println("[Linux] Error posting screen click: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Override
	public void focusWindow(GenericWindow window) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();
			X11.INSTANCE.XRaiseWindow(display, x11Window);
			X11.INSTANCE.XSetInputFocus(display, x11Window, X11.RevertToParent, 0);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error focusing window: " + e.getMessage());
		}
	}

	@Override
	public void moveWindow(GenericWindow window, int x, int y) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			X11.INSTANCE.XMoveWindow(display, (Pointer) window.getNativeHandle(), x, y);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error moving window: " + e.getMessage());
		}
	}

	@Override
	public void resizeWindow(GenericWindow window, int width, int height) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			X11.INSTANCE.XResizeWindow(display, (Pointer) window.getNativeHandle(), width, height);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error resizing window: " + e.getMessage());
		}
	}

	// --- Input synthesis ---

	private static final long KEYSYM_SHIFT_L = 0xFFE1L;

	@Override
	public void keyDown(int nativeKeyCode) {
		fakeKey(nativeKeyCode, true);
	}

	@Override
	public void keyUp(int nativeKeyCode) {
		fakeKey(nativeKeyCode, false);
	}

	/** nativeKeyCode is an X keysym; resolve it to a physical keycode for XTest. */
	private void fakeKey(int keysym, boolean press) {
		checkNotClosed();
		if (!x11Available) {
			return;
		}
		try {
			int keycode = X11.INSTANCE.XKeysymToKeycode(display, keysym) & 0xFF;
			if (keycode == 0) {
				return;
			}
			XTest.INSTANCE.XTestFakeKeyEvent(display, keycode, press, 0);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error posting key event: " + e.getMessage());
		}
	}

	@Override
	public void typeText(String text) {
		checkNotClosed();
		if (!x11Available || text == null) {
			return;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			// For Latin-1 the X keysym equals the code point; uppercase letters need Shift held.
			boolean needShift = Character.isUpperCase(c);
			if (needShift) {
				fakeKey((int) KEYSYM_SHIFT_L, true);
			}
			fakeKey(c, true);
			fakeKey(c, false);
			if (needShift) {
				fakeKey((int) KEYSYM_SHIFT_L, false);
			}
		}
	}

	@Override
	public void mouseMove(int xAbs, int yAbs) {
		checkNotClosed();
		if (!x11Available) {
			return;
		}
		try {
			XTest.INSTANCE.XTestFakeMotionEvent(display, -1, xAbs, yAbs, 0);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error moving mouse: " + e.getMessage());
		}
	}

	@Override
	public void mouseButton(int button, boolean press) {
		checkNotClosed();
		if (!x11Available) {
			return;
		}
		try {
			XTest.INSTANCE.XTestFakeButtonEvent(display, button, press, 0);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error posting mouse button: " + e.getMessage());
		}
	}

	@Override
	public void scroll(int amount) {
		checkNotClosed();
		if (!x11Available || amount == 0) {
			return;
		}
		int button = amount > 0 ? XTest.Button4 : XTest.Button5; // 4 = up/away, 5 = down/toward
		int ticks = Math.abs(amount);
		try {
			for (int i = 0; i < ticks; i++) {
				XTest.INSTANCE.XTestFakeButtonEvent(display, button, true, 0);
				XTest.INSTANCE.XTestFakeButtonEvent(display, button, false, 0);
			}
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error scrolling: " + e.getMessage());
		}
	}

	/**
	 * Convert X11 window to GenericWindow
	 */
	private GenericWindow toGenericWindow(Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return null;
		}

		try {
			String title = X11Utils.getWindowTitle(display, window);
			Rectangle rect = X11Utils.getWindowGeometry(display, window);

			if (rect == null) {
				rect = new Rectangle(0, 0, 0, 0);
			}

			return new GenericWindow(window, title != null ? title : "", rect);
		} catch (Exception e) {
			System.err.println("[Linux] Error converting to GenericWindow: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Cleanup X11 resources
	 * This method is safe to call multiple times.
	 */
	@Override
	public void close() {
		if (!closed && x11Available && display != null) {
			synchronized (this) {
				if (!closed) {
					try {
						X11.INSTANCE.XCloseDisplay(display);
					} catch (Exception e) {
						System.err.println("[Linux] Error closing X11 display: " + e.getMessage());
					} finally {
						closed = true;
					}
				}
			}
		}
	}

	/**
	 * Check if resources have been closed
	 */
	private void checkNotClosed() {
		if (closed) {
			throw new IllegalStateException("LinuxController has been closed and cannot be used");
		}
	}
}
