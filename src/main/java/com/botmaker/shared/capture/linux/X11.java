package com.botmaker.shared.capture.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

/**
 * JNA bindings for X11 library (libX11)
 * Used for window management and querying on Linux
 *
 * Note: XImage-related bindings removed since we use Robot for screen capture
 */
public interface X11 extends Library {
	X11 INSTANCE = Native.load("X11", X11.class);

	// Constants
	int None = 0;
	int AnyPropertyType = 0;
	int Success = 0;
	int InputFocus = 1;
	int RevertToParent = 2;

	int IsUnmapped = 0;
	int IsUnviewable = 1;
	int IsViewable = 2;

	// Event types (X.h)
	int KeyPress = 2;
	int KeyRelease = 3;
	int ButtonPress = 4;
	int ButtonRelease = 5;
	int MotionNotify = 6;

	// Event mask bits (X.h) — used as the XSendEvent event_mask so the event is delivered
	// to the window's owning client even when we target a specific window.
	long KeyPressMask = 1L << 0;
	long KeyReleaseMask = 1L << 1;
	long ButtonPressMask = 1L << 2;
	long ButtonReleaseMask = 1L << 3;
	long PointerMotionMask = 1L << 6;

	long CurrentTime = 0L;

	// Display management
	Pointer XOpenDisplay(String displayName);
	int XCloseDisplay(Pointer display);
	Pointer XDefaultRootWindow(Pointer display);
	int XDefaultScreen(Pointer display);
	Pointer XRootWindow(Pointer display, int screenNumber);
	int XFlush(Pointer display);
	int XSync(Pointer display, boolean discard);

	// Window queries
	int XQueryTree(Pointer display, Pointer window,
				   PointerByReference rootReturn,
				   PointerByReference parentReturn,
				   PointerByReference childrenReturn,
				   IntByReference nChildrenReturn);

	int XGetWindowProperty(Pointer display, Pointer window,
						   Pointer property, long longOffset, long longLength,
						   boolean delete, Pointer reqType,
						   PointerByReference actualTypeReturn,
						   IntByReference actualFormatReturn,
						   IntByReference nItemsReturn,
						   IntByReference bytesAfterReturn,
						   PointerByReference propReturn);

	// Window attributes
	int XGetWindowAttributes(Pointer display, Pointer window, XWindowAttributes attributes);

	int XGetGeometry(Pointer display, Pointer drawable,
					 PointerByReference rootReturn,
					 IntByReference xReturn, IntByReference yReturn,
					 IntByReference widthReturn, IntByReference heightReturn,
					 IntByReference borderWidthReturn,
					 IntByReference depthReturn);

	int XTranslateCoordinates(Pointer display, Pointer srcWindow, Pointer destWindow,
							  int srcX, int srcY,
							  IntByReference destXReturn, IntByReference destYReturn,
							  PointerByReference childReturn);

	// Pointer query (root-window coordinates, matching XTest's coordinate space)
	boolean XQueryPointer(Pointer display, Pointer window,
						  PointerByReference rootReturn, PointerByReference childReturn,
						  IntByReference rootXReturn, IntByReference rootYReturn,
						  IntByReference winXReturn, IntByReference winYReturn,
						  IntByReference maskReturn);

	// Window focus
	int XGetInputFocus(Pointer display, PointerByReference focusReturn, IntByReference revertToReturn);
	int XSetInputFocus(Pointer display, Pointer focus, int revertTo, long time);

	// Synthetic event delivery. Sends {@code event} to {@code window}'s owning client without moving the
	// real pointer (unlike XTest). {@code propagate}=false + a matching {@code eventMask} delivers the
	// event to the client even if it hasn't selected for it. Returns 0 on failure (BadWindow/BadValue).
	int XSendEvent(Pointer display, Pointer window, boolean propagate, com.sun.jna.NativeLong eventMask,
				   XButtonEvent event);

	// Window geometry mutation (move / resize)
	int XMoveWindow(Pointer display, Pointer window, int x, int y);
	int XResizeWindow(Pointer display, Pointer window, int width, int height);
	int XMoveResizeWindow(Pointer display, Pointer window, int x, int y, int width, int height);
	int XRaiseWindow(Pointer display, Pointer window);

	// Keyboard: map an X keysym to a physical keycode for XTest injection
	byte XKeysymToKeycode(Pointer display, long keysym);

	// Atoms
	Pointer XInternAtom(Pointer display, String atomName, boolean onlyIfExists);
	int XGetAtomName(Pointer display, Pointer atom, PointerByReference nameReturn);

	// Screen info
	int XScreenCount(Pointer display);
	int XDisplayWidth(Pointer display, int screenNumber);
	int XDisplayHeight(Pointer display, int screenNumber);

	// Memory management
	int XFree(Pointer data);

	// Utility
	int XFetchName(Pointer display, Pointer window, PointerByReference nameReturn);

	/**
	 * XButtonEvent / XKeyEvent — same native layout in Xlib.h (the trailing {@code button} field doubles as
	 * {@code keycode} for KeyPress/KeyRelease). Passed to {@link #XSendEvent} to deliver synthetic pointer or
	 * key input to a specific window without touching the real cursor.
	 *
	 * <p>The native {@code XEvent} is a union sized to its largest member (192 bytes on 64-bit). {@code
	 * XSendEvent} reads {@code sizeof(XEvent)} bytes from the pointer, so this struct is padded past that size
	 * with {@link #pad} to guarantee the read stays inside our buffer. Fields follow the Xlib.h order; JNA
	 * applies the natural C alignment (e.g. {@code serial}/{@code display} align to 8 on 64-bit).
	 */
	class XButtonEvent extends Structure {
		public int type;                       // ButtonPress/ButtonRelease/KeyPress/KeyRelease/MotionNotify
		public com.sun.jna.NativeLong serial;  // unsigned long — # of last request processed by server
		public int send_event;                 // Bool — set true by the server for XSendEvent'd events
		public Pointer display;                // Display* the event was read from
		public com.sun.jna.NativeLong window;  // Window the event is reported relative to (XID)
		public com.sun.jna.NativeLong root;    // root window that the event occurred on
		public com.sun.jna.NativeLong subwindow;
		public com.sun.jna.NativeLong time;    // Time — milliseconds
		public int x, y;                       // pointer position, window-relative
		public int x_root, y_root;             // pointer position, root-relative (screen)
		public int state;                      // key/button mask
		public int button;                     // button (1..5) — or keycode for KeyPress/KeyRelease
		public int same_screen;                // Bool
		// Pad past sizeof(XEvent) (192B on 64-bit) so XSendEvent never reads past our buffer.
		public byte[] pad = new byte[128];

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("type", "serial", "send_event", "display", "window", "root", "subwindow",
				"time", "x", "y", "x_root", "y_root", "state", "button", "same_screen", "pad");
		}
	}

	/**
	 * XWindowAttributes structure
	 */
	class XWindowAttributes extends Structure {
		public int x, y;
		public int width, height;
		public int border_width;
		public int depth;
		public Pointer visual;
		public Pointer root;
		public int c_class;
		public int bit_gravity;
		public int win_gravity;
		public int backing_store;
		public long backing_planes;
		public long backing_pixel;
		public boolean save_under;
		public Pointer colormap;
		public boolean map_installed;
		public int map_state;
		public long all_event_masks;
		public long your_event_mask;
		public long do_not_propagate_mask;
		public boolean override_redirect;
		public Pointer screen;

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("x", "y", "width", "height", "border_width", "depth",
				"visual", "root", "c_class", "bit_gravity", "win_gravity",
				"backing_store", "backing_planes", "backing_pixel",
				"save_under", "colormap", "map_installed", "map_state",
				"all_event_masks", "your_event_mask", "do_not_propagate_mask",
				"override_redirect", "screen");
		}
	}
}
