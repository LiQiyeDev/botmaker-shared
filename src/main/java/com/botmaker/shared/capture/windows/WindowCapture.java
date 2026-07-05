package com.botmaker.shared.capture.windows;

import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinGDI;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Per-window pixel capture on Windows. GDI {@code PrintWindow(PW_RENDERFULLCONTENT)} is the primary
 * path (works for most GPU/hardware-composited windows); it falls back to AWT {@link Robot} for
 * fullscreen windows and whenever GDI returns a black/invalid frame. Returns {@code null} if no usable
 * frame can be produced, so callers can apply their own full-desktop fallback.
 */
public final class WindowCapture {

	private WindowCapture() {}

	public static BufferedImage capture(HWND hWnd) {
		// Get screen dimensions for fullscreen check
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		RECT screenRect = new RECT();
		screenRect.left = 0;
		screenRect.top = 0;
		screenRect.right = (int) screenSize.getWidth();
		screenRect.bottom = (int) screenSize.getHeight();

		// Get window dimensions
		RECT windowRect = new RECT();
		User32.INSTANCE.GetWindowRect(hWnd.getPointer(), windowRect);

		BufferedImage image;

		// Check if the window is fullscreen. If so, Robot is more reliable.
		if (windowRect.toString().equals(screenRect.toString()) && User32.INSTANCE.GetForegroundWindow().equals(hWnd)) {
			image = captureWithRobot(windowRect);
		} else {
			// For windowed mode, GDI is faster, with a fallback to Robot.
			image = captureWithGDI(hWnd);
			// Fallback for black, invalid, or frozen (stale) images.
			if (image == null || image.getWidth() == 0 || image.getHeight() == 0 || isBlack(image)) {
				image = captureWithRobot(windowRect);
			}
		}
		return image;
	}

	private static BufferedImage captureWithGDI(HWND hWnd) {
		HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
		HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);

		RECT bounds = new RECT();
		User32.INSTANCE.GetClientRect(hWnd, bounds);

		int width = bounds.right - bounds.left;
		int height = bounds.bottom - bounds.top;

		if (width <= 0 || height <= 0) {
			User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
			GDI32.INSTANCE.DeleteDC(hdcMemDC);
			return null;
		}

		HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);
		GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap.getPointer());

		User32.INSTANCE.PrintWindow(hWnd, hdcMemDC, 2); // 2 = PW_RENDERFULLCONTENT

		WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
		bmi.bmiHeader.biWidth = width;
		bmi.bmiHeader.biHeight = -height; // Top-down image
		bmi.bmiHeader.biPlanes = 1;
		bmi.bmiHeader.biBitCount = 32;
		bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		GDI32.INSTANCE.GetDIBits(hdcWindow, hBitmap, 0, height, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), bmi, WinGDI.DIB_RGB_COLORS);

		GDI32.INSTANCE.DeleteObject(hBitmap);
		GDI32.INSTANCE.DeleteDC(hdcMemDC);
		User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);

		return image;
	}

	private static BufferedImage captureWithRobot(RECT bounds) {
		int width = bounds.right - bounds.left;
		int height = bounds.bottom - bounds.top;
		if (width <= 0 || height <= 0) {
			return null;
		}
		try {
			return new Robot().createScreenCapture(new Rectangle(bounds.left, bounds.top, width, height));
		} catch (AWTException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static boolean isBlack(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		// Check a few pixels to see if they are black. A small sample is enough.
		for (int i = 0; i < 10; i++) {
			int x = (int) (Math.random() * width);
			int y = (int) (Math.random() * height);
			if ((image.getRGB(x, y) & 0x00FFFFFF) != 0) {
				return false; // Found a non-black pixel
			}
		}
		return true;
	}
}
