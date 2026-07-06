package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.linux.input.LinuxInputBackend;
import com.botmaker.shared.capture.linux.input.UinputBackend;
import com.botmaker.shared.capture.linux.input.XSendEventBackend;
import com.botmaker.shared.capture.linux.input.XTestBackend;
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
 *
 * <p><b>Input synthesis</b> is delegated to a pluggable {@link LinuxInputBackend} chosen by the
 * {@code botmaker.linux.input} system property (or {@code BOTMAKER_LINUX_INPUT} env var):
 * <ul>
 *   <li>{@code auto} (default) / {@code xsendevent} — {@link XSendEventBackend}: cursor-preserving,
 *       background clicks to X11/XWayland windows. The real pointer is never moved.</li>
 *   <li>{@code uinput} — {@link UinputBackend}: reliable everywhere (incl. native Wayland/games) but moves
 *       the shared cursor; falls back to xsendevent if {@code /dev/uinput} can't be opened.</li>
 *   <li>{@code xtest} — {@link XTestBackend}: the legacy warp-and-click (moves the cursor).</li>
 * </ul>
 * {@code auto} never selects a cursor-moving backend.
 */
public class LinuxController implements NativeController, AutoCloseable {

	private final Pointer display;
	private final boolean x11Available;
	private final LinuxInputBackend inputBackend;
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
		this.inputBackend = available ? selectBackend(disp) : null;
	}

	/** Pick the input backend from {@code botmaker.linux.input} (default {@code auto} → cursor-safe xsendevent). */
	private static LinuxInputBackend selectBackend(Pointer display) {
		String env = System.getenv("BOTMAKER_LINUX_INPUT");
		String choice = System.getProperty("botmaker.linux.input", env != null ? env : "auto")
			.trim().toLowerCase();

		LinuxInputBackend backend;
		switch (choice) {
			case "xtest":
				backend = new XTestBackend(display);
				break;
			case "uinput": {
				int screen = X11.INSTANCE.XDefaultScreen(display);
				int w = X11.INSTANCE.XDisplayWidth(display, screen);
				int h = X11.INSTANCE.XDisplayHeight(display, screen);
				UinputBackend u = UinputBackend.tryCreate(w, h, display);
				if (u == null) {
					System.err.println("[Linux] uinput unavailable (can't open /dev/uinput); "
						+ "falling back to cursor-safe xsendevent.");
					backend = new XSendEventBackend(display);
				} else {
					backend = u;
				}
				break;
			}
			case "auto":
			case "xsendevent":
			default:
				backend = new XSendEventBackend(display);
				break;
		}
		System.out.println("[Linux] input backend = " + backend.name()
			+ " (preservesCursor=" + backend.preservesCursor() + ")");
		return backend;
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
	 * Post a left click at window-relative coordinates via the selected input backend. With the default
	 * {@code xsendevent} backend this delivers the click straight to the window without moving the real
	 * cursor, so it works in the background.
	 */
	@Override
	public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
		checkNotClosed();
		if (inputBackend == null || window == null) {
			return;
		}
		inputBackend.clickWindow((Pointer) window.getNativeHandle(), relativeX, relativeY, 1);
	}

	/**
	 * Post a left click at absolute screen coordinates via the selected input backend. The default
	 * {@code xsendevent} backend resolves the window under the point and clicks it without touching the
	 * real cursor; {@code uinput}/{@code xtest} move the shared cursor.
	 */
	@Override
	public void postLeftClickScreen(int xAbs, int yAbs) {
		checkNotClosed();
		if (inputBackend == null) {
			System.out.println("[Linux] X11 not available, cannot post click.");
			return;
		}
		inputBackend.clickScreen(xAbs, yAbs, 1);
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

	// --- Input synthesis (delegated to the selected LinuxInputBackend) ---

	private static final int KEYSYM_SHIFT_L = 0xFFE1;

	@Override
	public void keyDown(int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.key(nativeKeyCode, true);
		}
	}

	@Override
	public void keyUp(int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.key(nativeKeyCode, false);
		}
	}

	@Override
	public void typeText(String text) {
		checkNotClosed();
		if (inputBackend == null || text == null) {
			return;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			// For Latin-1 the X keysym equals the code point; uppercase letters need Shift held.
			boolean needShift = Character.isUpperCase(c);
			if (needShift) {
				inputBackend.key(KEYSYM_SHIFT_L, true);
			}
			inputBackend.key(c, true);
			inputBackend.key(c, false);
			if (needShift) {
				inputBackend.key(KEYSYM_SHIFT_L, false);
			}
		}
	}

	@Override
	public void mouseMove(int xAbs, int yAbs) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.move(xAbs, yAbs);
		}
	}

	@Override
	public void mouseButton(int button, boolean press) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.button(button, press);
		}
	}

	@Override
	public void scroll(int amount) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.scroll(amount);
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
						if (inputBackend != null) {
							inputBackend.close(); // destroy the uinput device, if any
						}
					} catch (Exception e) {
						System.err.println("[Linux] Error closing input backend: " + e.getMessage());
					}
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

	/** True if the active input backend leaves the user's real cursor untouched (background-capable). */
	@Override
	public boolean supportsBackgroundInput() {
		return inputBackend != null && inputBackend.preservesCursor();
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
