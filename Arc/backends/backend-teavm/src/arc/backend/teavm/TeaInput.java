package arc.backend.teavm;

import arc.Input;
import arc.input.KeyCode;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;

/** Keyboard, mouse and wheel input, matching the desktop SDL backend's event semantics. */
public final class TeaInput extends Input{
    /** Pixels per wheel notch in Chromium and Safari (DOM_DELTA_PIXEL). */
    private static final double pixelsPerNotch = 100d;
    /** Lines per wheel notch in Firefox (DOM_DELTA_LINE). */
    private static final double linesPerNotch = 3d;

    private final TeaGraphics graphics;
    private double wheelX;
    private double wheelY;
    private boolean pasteKeyPending;
    private int mouseX;
    private int mouseY;
    private int deltaX;
    private int deltaY;
    private boolean touched;
    private boolean justTouched;
    private long currentEventTime;
    private final boolean[] pressedKeys = new boolean[KeyCode.values().length];

    public TeaInput(TeaGraphics graphics){
        this.graphics = graphics;
        HTMLCanvasElement canvas = graphics.getCanvas();

        // Keep secondary click available to the game instead of opening the browser's image menu.
        canvas.addEventListener("contextmenu", raw -> raw.preventDefault());
        canvas.addEventListener("dragstart", raw -> raw.preventDefault());
        canvas.addEventListener("mousemove", raw -> {
            MouseEvent event = (MouseEvent)raw;
            updatePointer(event);
            inputMultiplexer.mouseMoved(mouseX, mouseY);
        });
        canvas.addEventListener("mousedown", raw -> {
            MouseEvent event = (MouseEvent)raw;
            updatePointer(event);
            touched = true;
            justTouched = true;
            inputMultiplexer.touchDown(mouseX, mouseY, 0, mouseButton(event.getButton()));
            raw.preventDefault();
        });
        Window.current().addEventListener("mouseup", raw -> {
            MouseEvent event = (MouseEvent)raw;
            updatePointer(event);
            touched = false;
            inputMultiplexer.touchUp(mouseX, mouseY, 0, mouseButton(event.getButton()));
        });
        canvas.addEventListener("wheel", raw -> {
            WheelEvent event = (WheelEvent)raw;
            currentEventTime = System.nanoTime();
            scrolled(event);
            raw.preventDefault();
        });
        Window.current().addEventListener("keydown", raw -> {
            KeyboardEvent event = (KeyboardEvent)raw;
            KeyCode key = key(event.getCode());
            currentEventTime = System.nanoTime();
            if(gameHasFocus() && isBrowserShortcut(key, event)) raw.preventDefault();

            boolean command = (event.isCtrlKey() || event.isMetaKey()) && !event.isAltKey();
            // Ctrl+V: the page's clipboard text only arrives in the paste event that follows,
            // so hold the key press until then (see pasted()).
            if(command && key == KeyCode.v && !pressedKeys[key.ordinal()]){
                pressedKeys[key.ordinal()] = true;
                pasteKeyPending = true;
                Window.setTimeout(this::releasePendingPaste, 100);
                return;
            }

            // Browsers repeat keydown while a key is held. Arc expects keyDown once per
            // physical press; repeated events otherwise turn keyTap(B/F) true every frame
            // and continually reset Mindustry's drag-selection origin.
            if(!pressedKeys[key.ordinal()]){
                pressedKeys[key.ordinal()] = true;
                inputMultiplexer.keyDown(key);
            }

            // Editing keys type control characters, as on desktop (repeats included).
            if(key == KeyCode.backspace) inputMultiplexer.keyTyped((char)8);
            else if(key == KeyCode.tab) inputMultiplexer.keyTyped('\t');
            else if(key == KeyCode.enter) inputMultiplexer.keyTyped((char)13);
            else if(key == KeyCode.forwardDel) inputMultiplexer.keyTyped((char)127);
            else if(!command){
                // Ctrl/Cmd shortcuts must not also type their letter (Ctrl+A would replace the selection).
                String text = event.getKey();
                if(text != null && (text.length() == 1 || text.length() == 2 && Character.isHighSurrogate(text.charAt(0)))){
                    for(int i = 0; i < text.length(); i++) inputMultiplexer.keyTyped(text.charAt(i));
                }
            }
        });
        Window.current().addEventListener("keyup", raw -> {
            KeyboardEvent event = (KeyboardEvent)raw;
            KeyCode key = key(event.getCode());
            currentEventTime = System.nanoTime();
            pressedKeys[key.ordinal()] = false;
            inputMultiplexer.keyUp(key);
        });
        Window.current().addEventListener("blur", raw -> {
            for(int ordinal = 0; ordinal < pressedKeys.length; ordinal++){
                if(pressedKeys[ordinal]){
                    pressedKeys[ordinal] = false;
                    inputMultiplexer.keyUp(KeyCode.byOrdinal(ordinal));
                }
            }
        });
    }

    /** Called by the application when the browser delivers pasted text. */
    void pasted(){
        releasePendingPaste();
    }

    private void releasePendingPaste(){
        if(!pasteKeyPending) return;
        pasteKeyPending = false;
        if(pressedKeys[KeyCode.v.ordinal()]) inputMultiplexer.keyDown(KeyCode.v);
    }

    /**
     * Converts wheel deltas to the desktop convention of one unit per notch.
     * Trackpads send many small pixel deltas; they accumulate into whole steps.
     */
    private void scrolled(WheelEvent event){
        double scale;
        switch(event.getDeltaMode()){
            case WheelEvent.DOM_DELTA_LINE: scale = 1d / linesPerNotch; break;
            case WheelEvent.DOM_DELTA_PAGE: scale = 1d; break;
            default: scale = 1d / pixelsPerNotch; break;
        }
        wheelX = accumulate(wheelX, event.getDeltaX() * scale);
        wheelY = accumulate(wheelY, event.getDeltaY() * scale);
        int stepsX = step(wheelX), stepsY = step(wheelY);
        // A single real notch always scrolls, even if a browser reports a smaller delta.
        if(stepsX == 0 && stepsY == 0 && event.getDeltaMode() != WheelEvent.DOM_DELTA_PIXEL){
            stepsX = (int)Math.signum(wheelX);
            stepsY = (int)Math.signum(wheelY);
        }
        if(stepsX == 0 && stepsY == 0) return;
        wheelX -= stepsX;
        wheelY -= stepsY;
        inputMultiplexer.scrolled(stepsX, stepsY);
    }

    private static double accumulate(double total, double delta){
        // Reversing direction discards the leftover from the old direction.
        if(delta != 0d && Math.signum(delta) != Math.signum(total)) total = 0d;
        return total + delta;
    }

    private static int step(double total){
        return total >= 1d ? (int)Math.floor(total) : total <= -1d ? (int)Math.ceil(total) : 0;
    }

    /** Keys the page would otherwise act on (scrolling, focus changes, reload, search) while playing. */
    private static boolean isBrowserShortcut(KeyCode key, KeyboardEvent event){
        switch(key){
            case tab: case space: case up: case down: case left: case right:
            case pageUp: case pageDown: case home: case end: case backspace:
            case f1: case f3: case f5: case f6: case f7: case f8: case f11:
                return true;
            case s: case f: case p: case d: case g: case h: case o: case a:
                // Save, find, print, bookmark and select-all dialogs; the game uses these with Ctrl.
                // Clipboard, reload and tab shortcuts keep their browser behaviour.
                return (event.isCtrlKey() || event.isMetaKey()) && !event.isShiftKey();
            default:
                return false;
        }
    }

    /** True unless a page control (such as an overlay button) has keyboard focus. */
    @JSBody(script = "var active = document.activeElement; return !active || active === document.body || active.tagName === 'CANVAS';")
    private static native boolean gameHasFocus();

    /** Clears one-frame state after all listeners have observed the current frame. */
    public void postUpdate(){
        deltaX = 0;
        deltaY = 0;
        justTouched = false;
        keyboard.postUpdate();
    }

    private void updatePointer(MouseEvent event){
        int nextX = event.getOffsetX();
        int nextY = graphics.getHeight() - event.getOffsetY();
        deltaX += nextX - mouseX;
        deltaY += nextY - mouseY;
        mouseX = nextX;
        mouseY = nextY;
        currentEventTime = System.nanoTime();
        if(touched) inputMultiplexer.touchDragged(mouseX, mouseY, 0);
    }

    @Override
    public int mouseX(){
        return mouseX;
    }

    @Override
    public int mouseX(int pointer){
        return pointer == 0 ? mouseX : 0;
    }

    @Override
    public int deltaX(){
        return deltaX;
    }

    @Override
    public int deltaX(int pointer){
        return pointer == 0 ? deltaX : 0;
    }

    @Override
    public int mouseY(){
        return mouseY;
    }

    @Override
    public int mouseY(int pointer){
        return pointer == 0 ? mouseY : 0;
    }

    @Override
    public int deltaY(){
        return deltaY;
    }

    @Override
    public int deltaY(int pointer){
        return pointer == 0 ? deltaY : 0;
    }

    @Override
    public boolean isTouched(){
        return touched;
    }

    @Override
    public boolean justTouched(){
        return justTouched;
    }

    @Override
    public boolean isTouched(int pointer){
        return pointer == 0 && touched;
    }

    @Override
    public long getCurrentEventTime(){
        return currentEventTime;
    }

    private static KeyCode mouseButton(short button){
        switch(button){
            case MouseEvent.RIGHT_BUTTON: return KeyCode.mouseRight;
            case MouseEvent.MIDDLE_BUTTON: return KeyCode.mouseMiddle;
            case 3: return KeyCode.mouseBack;
            case 4: return KeyCode.mouseForward;
            default: return KeyCode.mouseLeft;
        }
    }

    private static KeyCode key(String code){
        if(code == null) return KeyCode.unknown;
        if(code.length() == 4 && code.startsWith("Key")){
            char letter = Character.toLowerCase(code.charAt(3));
            if(letter >= 'a' && letter <= 'z') return KeyCode.valueOf(String.valueOf(letter));
        }
        if(code.length() == 6 && code.startsWith("Digit")){
            char number = code.charAt(5);
            if(number >= '0' && number <= '9') return KeyCode.valueOf("num" + number);
        }
        if(code.length() == 7 && code.startsWith("Numpad")){
            char number = code.charAt(6);
            if(number >= '0' && number <= '9') return KeyCode.valueOf("numpad" + number);
        }
        if(code.length() >= 2 && code.length() <= 3 && code.charAt(0) == 'F'){
            String number = code.substring(1);
            if(number.equals("1") || number.equals("2") || number.equals("3") || number.equals("4")
            || number.equals("5") || number.equals("6") || number.equals("7") || number.equals("8")
            || number.equals("9") || number.equals("10") || number.equals("11") || number.equals("12")){
                return KeyCode.valueOf("f" + number);
            }
        }
        switch(code){
            case "ArrowUp": return KeyCode.up;
            case "ArrowDown": return KeyCode.down;
            case "ArrowLeft": return KeyCode.left;
            case "ArrowRight": return KeyCode.right;
            case "Enter": return KeyCode.enter;
            case "Escape": return KeyCode.escape;
            case "Space": return KeyCode.space;
            case "Tab": return KeyCode.tab;
            case "Backspace": return KeyCode.backspace;
            case "Delete": return KeyCode.forwardDel;
            case "ShiftLeft": return KeyCode.shiftLeft;
            case "ShiftRight": return KeyCode.shiftRight;
            case "ControlLeft": return KeyCode.controlLeft;
            case "ControlRight": return KeyCode.controlRight;
            case "AltLeft": return KeyCode.altLeft;
            case "AltRight": return KeyCode.altRight;
            case "Comma": return KeyCode.comma;
            case "Period": return KeyCode.period;
            case "BracketLeft": return KeyCode.leftBracket;
            case "BracketRight": return KeyCode.rightBracket;
            case "Minus": return KeyCode.minus;
            case "Equal": return KeyCode.equals;
            case "Semicolon": return KeyCode.semicolon;
            case "Quote": return KeyCode.apostrophe;
            case "Backquote": return KeyCode.backtick;
            case "Backslash": return KeyCode.backslash;
            case "Slash": return KeyCode.slash;
            case "Home": return KeyCode.home;
            case "End": return KeyCode.end;
            case "PageUp": return KeyCode.pageUp;
            case "PageDown": return KeyCode.pageDown;
            case "Insert": return KeyCode.insert;
            case "CapsLock": return KeyCode.capsLock;
            case "Pause": return KeyCode.pause;
            case "NumpadEnter": return KeyCode.enter;
            case "NumpadAdd": return KeyCode.plus;
            case "NumpadSubtract": return KeyCode.minus;
            case "NumpadMultiply": return KeyCode.asterisk;
            case "NumpadDivide": return KeyCode.slash;
            case "NumpadDecimal": return KeyCode.period;
            case "MetaLeft": case "MetaRight": return KeyCode.sym;
            default: return KeyCode.unknown;
        }
    }
}
