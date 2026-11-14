package com.laiba.rebuilt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** LAIBA's hands and eyes: reads the screen as numbered items and performs gestures/actions on request. */
public class LaibaService extends AccessibilityService {
    static volatile LaibaService inst;
    private ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private ArrayList<String> labels = new ArrayList<>();
    private AccessibilityNodeInfo lastInput;

    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(Intent i) { inst = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    /** Text snapshot of the visible screen. Each interactive/text item gets an id. */
    synchronized String dump() {
        nodes = new ArrayList<>();
        labels = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(screen not readable right now)";
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder("app: " + root.getPackageName() + "\nscreen: " + dm.widthPixels + "x" + dm.heightPixels + "\n");
        walk(root, sb, 0);
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || nodes.size() >= 90 || depth > 40) return;
        CharSequence cs = n.getText() != null ? n.getText() : n.getContentDescription();
        String t = cs == null ? "" : cs.toString().replace("\n", " ");
        if (t.length() > 80) t = t.substring(0, 80);
        String kind = null;
        if (n.isEditable()) kind = "input";
        else if (n.isCheckable()) kind = n.isChecked() ? "switch(on)" : "switch(off)";
        else if (n.isClickable() && !t.trim().isEmpty()) kind = "button";
        else if (n.isClickable()) kind = "button(no label)";
        else if (!t.trim().isEmpty()) kind = "text";
        if (kind != null && n.isVisibleToUser()) {
            nodes.add(n);
            labels.add(t);
            sb.append("[").append(nodes.size() - 1).append("] ").append(kind).append(" \"").append(t).append("\"\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb, depth + 1);
    }

    String label(int i) { return i >= 0 && i < labels.size() ? labels.get(i) : ""; }
    String currentApp() { AccessibilityNodeInfo r = getRootInActiveWindow(); return r == null ? "" : String.valueOf(r.getPackageName()); }

    boolean click(int i) {
        if (i < 0 || i >= nodes.size()) return false;
        AccessibilityNodeInfo orig = nodes.get(i), n = orig;
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        orig.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY(), 60);
    }

    boolean longClick(int i) {
        if (i < 0 || i >= nodes.size()) return false;
        AccessibilityNodeInfo orig = nodes.get(i), n = orig;
        while (n != null && !n.isLongClickable()) n = n.getParent();
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
        Rect r = new Rect();
        orig.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY(), 700);
    }

    boolean tap(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null);
    }

    boolean swipe(String dir) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels, x1 = w / 2, y1 = h / 2, x2 = w / 2, y2 = h / 2;
        switch (dir) {
            case "up": y1 = h * 0.72f; y2 = h * 0.28f; break;   // finger moves up = content scrolls down
            case "down": y1 = h * 0.28f; y2 = h * 0.72f; break;
            case "left": x1 = w * 0.8f; x2 = w * 0.2f; break;
            case "right": x1 = w * 0.2f; x2 = w * 0.8f; break;
            default: return false;
        }
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
    }

    boolean type(int i, String text) {
        if (i < 0 || i >= nodes.size()) return false;
        AccessibilityNodeInfo n = nodes.get(i);
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        lastInput = n;
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean enter() {
        if (lastInput == null) return false;
        if (Build.VERSION.SDK_INT >= 30)
            return lastInput.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        return false;
    }

    boolean scroll(boolean down) {
        AccessibilityNodeInfo s = findScrollable(getRootInActiveWindow(), 0);
        if (s == null) return false;
        return s.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    boolean recents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    boolean notifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    boolean quickSettings() { return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }
    boolean lockScreen() { return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
    boolean screenshotToGallery() { return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); }

    /** Downscaled JPEG (base64) of the current screen for vision models. Android 11+. */
    String shot() {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                try {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    Bitmap b = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
                    hb.close();
                    int w = 720;
                    Bitmap s = Bitmap.createScaledBitmap(copy, w, Math.max(1, copy.getHeight() * w / copy.getWidth()), true);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    s.compress(Bitmap.CompressFormat.JPEG, 65, bo);
                    out[0] = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                } catch (Exception ignored) { }
                latch.countDown();
            }
            @Override public void onFailure(int code) { latch.countDown(); }
        });
        try { latch.await(6, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out[0];
    }
}
