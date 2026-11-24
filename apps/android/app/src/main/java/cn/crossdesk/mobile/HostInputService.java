package cn.crossdesk.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes remote input on this device through the accessibility API
 * (the "accessibility mode" injection path: no root, no ADB).
 *
 * Receives RemoteAction JSON forwarded from the native host peer:
 *   mouse    {"type":0,"mouse":{"x":0..1,"y":0..1,"s":wheel,"flag":MouseFlag}}
 *   keyboard {"type":1,"keyboard":{"key_value":VK,"flag":0 down / 1 up}}
 *   text     {"type":13,"text":"committed UTF-8 text"}
 *
 * Mouse coordinates are normalized to the controlled display. A press/drag/
 * release sequence is replayed as one accessibility stroke, so taps and
 * drags work; hover-only interactions cannot be represented. Keyboard input
 * uses Windows virtual-key codes (the wire format): printable keys are
 * turned into text inserted into the focused editable node, which is the
 * only text path the accessibility API offers.
 */
final class HostInputService extends AccessibilityService {
    static volatile HostInputService instance;

    private HandlerThread thread;
    private Handler handler;
    private int screenWidth = 1, screenHeight = 1;

    // Pointer state for the in-progress left-button stroke.
    private boolean dragging;
    private final List<PointF> dragPath = new ArrayList<>();
    private long downTimeMs;
    private boolean shiftDown;

    static void dispatch(String json) {
        HostInputService service = instance;
        if (service != null && service.handler != null) {
            service.handler.post(() -> service.handle(json));
        }
    }

    static boolean isConnected() { return instance != null; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        thread = new HandlerThread("crossdesk-host-input");
        thread.start();
        handler = new Handler(thread.getLooper());
        refreshScreenSize();
        // Keep-alive anchor: the system holds accessibility bindings far
        // longer than ordinary processes, so a (re)connect here revives the
        // host service after the process was reaped (the RustDesk pattern).
        if (BootReceiver.hostModeEnabled(this) && !HostService.isRunning()) {
            try {
                startForegroundService(new Intent(this, HostService.class)
                        .setAction(HostService.ACTION_START));
            } catch (Exception ignored) { }
        }
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    private void refreshScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        if (size.x > 0 && size.y > 0) { screenWidth = size.x; screenHeight = size.y; }
    }

    private void handle(String json) {
        try {
            JSONObject action = new JSONObject(json);
            int type = action.optInt("type", -1);
            if (type == 0) handleMouse(action.getJSONObject("mouse"));
            else if (type == 1) handleKeyboard(action.getJSONObject("keyboard"));
            else if (type == 13) insertText(action.optString("text", ""));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ mouse

    private void handleMouse(JSONObject mouse) {
        float nx = (float) mouse.optDouble("x", 0), ny = (float) mouse.optDouble("y", 0);
        int flag = mouse.optInt("flag", 0);
        int wheel = mouse.optInt("s", 0);
        float px = clamp(nx, 0f, 1f) * (screenWidth - 1);
        float py = clamp(ny, 0f, 1f) * (screenHeight - 1);
        switch (flag) {
            case 0: // move
                if (dragging) addDragPoint(px, py);
                break;
            case 1: // left_down
                refreshScreenSize();
                dragging = true;
                dragPath.clear();
                dragPath.add(new PointF(px, py));
                downTimeMs = android.os.SystemClock.uptimeMillis();
                break;
            case 2: // left_up
                if (dragging) {
                    addDragPoint(px, py);
                    long duration = android.os.SystemClock.uptimeMillis() - downTimeMs;
                    boolean tap = dragPath.size() <= 2 && duration < 400 &&
                            dragDistance() < screenWidth * 0.01f;
                    if (tap) stroke(dragPath.get(0).x, dragPath.get(0).y, dragPath.get(0).x, dragPath.get(0).y, 60);
                    else strokePath(dragPath, Math.max(50, Math.min(duration, 5000)));
                    dragging = false;
                    dragPath.clear();
                } else {
                    stroke(px, py, px, py, 60);
                }
                break;
            case 4: // right_up
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case 6: // middle_up
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case 7: // wheel vertical: s > 0 is wheel up (content scrolls up, finger swipes down)
                if (wheel != 0) {
                    float d = screenHeight / 4f;
                    float from = wheel > 0 ? py - d : py + d;
                    float to = wheel > 0 ? py + d : py - d;
                    stroke(px, clamp(from, 0, screenHeight - 1), px, clamp(to, 0, screenHeight - 1), 180);
                }
                break;
            case 8: // wheel horizontal
                if (wheel != 0) {
                    float d = screenWidth / 4f;
                    float from = wheel > 0 ? px - d : px + d;
                    float to = wheel > 0 ? px + d : px - d;
                    stroke(clamp(from, 0, screenWidth - 1), py, clamp(to, 0, screenWidth - 1), py, 180);
                }
                break;
            default:
                break;
        }
    }

    private void addDragPoint(float px, float py) {
        if (dragPath.isEmpty()) { dragPath.add(new PointF(px, py)); return; }
        PointF last = dragPath.get(dragPath.size() - 1);
        if (Math.hypot(px - last.x, py - last.y) >= 4 && dragPath.size() < 64) {
            dragPath.add(new PointF(px, py));
        }
    }

    private float dragDistance() {
        if (dragPath.size() < 2) return 0;
        PointF a = dragPath.get(0), b = dragPath.get(dragPath.size() - 1);
        return (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private void stroke(float x0, float y0, float x1, float y1, long durationMs) {
        Path path = new Path();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)))
                .build(), null, null);
    }

    private void strokePath(List<PointF> points, long durationMs) {
        if (points.isEmpty()) return;
        Path path = new Path();
        path.moveTo(points.get(0).x, points.get(0).y);
        for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x, points.get(i).y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs)))
                .build(), null, null);
    }

    // --------------------------------------------------------------- keyboard

    private void handleKeyboard(JSONObject keyboard) {
        int vk = keyboard.optInt("key_value", 0);
        boolean down = keyboard.optInt("flag", 0) == 0;
        if (vk == 16 || vk == 160 || vk == 161) { shiftDown = down; return; }
        if (!down) return;
        switch (vk) {
            case 8: deleteBackward(); return;
            case 13: enter(); return;
            case 27: performGlobalAction(GLOBAL_ACTION_BACK); return;
            case 36: performGlobalAction(GLOBAL_ACTION_HOME); return;
            case 33: stroke(screenWidth / 2f, screenHeight * 0.25f, screenWidth / 2f, screenHeight * 0.75f, 200); return;
            case 34: stroke(screenWidth / 2f, screenHeight * 0.75f, screenWidth / 2f, screenHeight * 0.25f, 200); return;
            case 37: case 38: case 39: case 40: moveFocus(vk); return;
            default:
                char ch = vkToChar(vk);
                if (ch != 0) insertText(String.valueOf(ch));
        }
    }

    private char vkToChar(int vk) {
        if (vk >= 65 && vk <= 90) return (char) (shiftDown ? vk : vk + 32); // A-Z
        if (vk >= 48 && vk <= 57) { // 0-9 row
            if (!shiftDown) return (char) vk;
            return ")!@#$%^&*(".charAt(vk - 48);
        }
        if (vk >= 96 && vk <= 105) return (char) ('0' + (vk - 96)); // numpad digits
        switch (vk) {
            case 32: return ' ';
            case 106: return '*';
            case 107: return '+';
            case 109: return '-';
            case 110: return '.';
            case 111: return '/';
            case 186: return shiftDown ? ':' : ';';
            case 187: return shiftDown ? '+' : '=';
            case 188: return shiftDown ? '<' : ',';
            case 189: return shiftDown ? '_' : '-';
            case 190: return shiftDown ? '>' : '.';
            case 191: return shiftDown ? '?' : '/';
            case 192: return shiftDown ? '~' : '`';
            case 219: return shiftDown ? '{' : '[';
            case 220: return shiftDown ? '|' : '\\';
            case 221: return shiftDown ? '}' : ']';
            case 222: return shiftDown ? '"' : '\'';
            default: return 0;
        }
    }

    private void moveFocus(int vk) {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) return;
        int direction = vk == 37 ? android.view.View.FOCUS_LEFT
                : vk == 39 ? android.view.View.FOCUS_RIGHT
                : vk == 38 ? android.view.View.FOCUS_UP : android.view.View.FOCUS_DOWN;
        AccessibilityNodeInfo next = focus.focusSearch(direction);
        if (next != null) {
            next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            next.recycle();
        }
        focus.recycle();
    }

    // ------------------------------------------------------------------- text

    private AccessibilityNodeInfo editableFocus() {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node != null && node.isEditable()) return node;
        if (node != null) node.recycle();
        node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (node != null && node.isEditable()) return node;
        if (node != null) node.recycle();
        return null;
    }

    private boolean setText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    void insertText(String text) {
        if (text == null || text.isEmpty()) return;
        AccessibilityNodeInfo node = editableFocus();
        if (node != null) {
            CharSequence current = node.getText();
            boolean ok = setText(node, (current == null ? "" : current.toString()) + text);
            node.recycle();
            if (ok) return;
        }
        // Fallback: put the text on the clipboard and ask the focused field to paste.
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("CrossDesk", text));
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) {
            focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            focus.recycle();
        }
    }

    private void deleteBackward() {
        AccessibilityNodeInfo node = editableFocus();
        if (node == null) return;
        CharSequence current = node.getText();
        if (current != null && current.length() > 0) {
            String text = current.toString();
            int cut = text.offsetByCodePoints(text.length(), -1);
            setText(node, text.substring(0, cut));
        }
        node.recycle();
    }

    private void enter() {
        AccessibilityNodeInfo node = editableFocus();
        if (node == null) return;
        boolean done = false;
        if (Build.VERSION.SDK_INT >= 30) {
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                if (action.getId() == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()) {
                    done = node.performAction(action.getId());
                    break;
                }
            }
        }
        if (!done && node.isMultiLine()) {
            CharSequence current = node.getText();
            setText(node, (current == null ? "" : current.toString()) + "\n");
        }
        node.recycle();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
