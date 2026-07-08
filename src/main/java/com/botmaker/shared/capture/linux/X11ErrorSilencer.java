package com.botmaker.shared.capture.linux;

import com.sun.jna.Pointer;

/**
 * Installs a no-op Xlib error handler so benign, non-fatal X protocol errors
 * (BadMatch/BadWindow/BadDrawable — e.g. an {@code XGetImage} racing a window unmap during window capture)
 * are swallowed instead of being dumped to stderr by Xlib's default handler.
 *
 * <p><b>Install order matters.</b> Calling {@code XSetErrorHandler} <em>after</em> the JavaFX GTK backend
 * (GDK) has initialized triggers GDK's own {@code "XSetErrorHandler() called with a GDK error trap pushed.
 * Don't do that."} warning. Install this <em>before</em> {@code Application.launch(...)} (see the Studio
 * {@code Launcher}) so our handler is the baseline the rest of the process inherits.
 *
 * <p>Only relevant on Linux/X11; a no-op (caught {@link Throwable}) elsewhere or when libX11 is absent.
 */
public final class X11ErrorSilencer {

	// Strong reference kept so the JNA callback is never GC'd while native code holds its pointer.
	private static X11.XErrorHandler handler;
	private static boolean installed;

	private X11ErrorSilencer() {}

	/** Idempotently installs the swallowing handler. Best-effort — never throws. */
	public static synchronized void install() {
		if (installed) return;
		try {
			handler = (display, errorEvent) -> 0; // swallow: non-fatal X errors are logged, not acted on
			X11.INSTANCE.XSetErrorHandler(handler);
			installed = true;
		} catch (Throwable ignored) {
			// Non-Linux, headless, or libX11 not present — nothing to silence.
		}
	}
}
