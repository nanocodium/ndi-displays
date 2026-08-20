package dev.nano.ndidisplays.client.web;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the embedded Chromium browsers behind the web terminals — one per terminal block —
 * and exposes each one's GL texture so it can be drawn and published as NDI.
 *
 * Reached entirely through reflection against MCEF's public API. MCEF is an optional companion
 * mod (it ships the ~200MB Chromium runtime), so a hard dependency would make this mod
 * unloadable without it; resolving lazily means a missing or renamed class degrades to a dark
 * screen and a log line instead of breaking the game. This is also why no WebDisplays code is
 * reused here: that project is separately licensed, and MCEF's own API is the actual interface
 * both mods are written against.
 *
 * Client only. Browsers are heavyweight — each is a Chromium render process — so they are
 * created on demand, capped, and closed as soon as their terminal is gone.
 */
public final class WebBrowsers {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PKG = "com.cinemamod.mcef";

    /**
     * Most browsers alive at once. A stage could hold a dozen terminals, and each one is a
     * Chromium process — well past the point where the machine, not the mod, becomes the limit.
     * The least recently drawn is retired to make room.
     */
    private static final int MAX_BROWSERS = 4;

    private enum State { UNKNOWN, READY, ABSENT }

    private static State state = State.UNKNOWN;
    private static Method mIsInitialized;
    private static Method mCreateBrowser;
    private static Method mGetRenderer;
    private static Method mGetTextureId;
    private static Method mResize;
    private static Method mLoadUrl;
    private static Method mClose;
    private static Method mMouseMove;
    private static Method mMousePress;
    private static Method mMouseRelease;
    private static Method mMouseWheel;
    private static Method mKeyPress;
    private static Method mKeyRelease;
    private static Method mKeyTyped;

    /** One browser per terminal, keyed by block position. */
    private static final Map<BlockPos, Session> SESSIONS = new ConcurrentHashMap<>();

    private WebBrowsers() {
    }

    /** A terminal's browser plus the state needed to keep it in step with the block. */
    public static final class Session {
        final Object browser;
        String url;
        int width;
        int height;
        /** Game time of the last draw, so idle terminals are retired before busy ones. */
        long lastUsed;

        Session(Object browser, String url, int width, int height) {
            this.browser = browser;
            this.url = url;
            this.width = width;
            this.height = height;
        }

        public int textureId() {
            try {
                Object renderer = mGetRenderer.invoke(browser);
                return renderer == null ? 0 : (int) mGetTextureId.invoke(renderer);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return 0;
            }
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    // ------------------------------------------------------------------ availability

    private static synchronized boolean resolve() {
        if (state != State.UNKNOWN) {
            return state == State.READY;
        }
        try {
            Class<?> mcef = Class.forName(PKG + ".MCEF");
            Class<?> browser = Class.forName(PKG + ".MCEFBrowser");
            Class<?> renderer = Class.forName(PKG + ".MCEFRenderer");

            mIsInitialized = mcef.getMethod("isInitialized");
            mCreateBrowser = mcef.getMethod("createBrowser", String.class, boolean.class,
                    int.class, int.class);
            mGetRenderer = browser.getMethod("getRenderer");
            mGetTextureId = renderer.getMethod("getTextureID");
            mResize = browser.getMethod("resize", int.class, int.class);
            // Inherited from CefBrowser, not declared on MCEFBrowser — getMethod walks up.
            mLoadUrl = browser.getMethod("loadURL", String.class);
            mClose = browser.getMethod("close");
            mMouseMove = browser.getMethod("sendMouseMove", int.class, int.class);
            mMousePress = browser.getMethod("sendMousePress", int.class, int.class, int.class);
            mMouseRelease = browser.getMethod("sendMouseRelease", int.class, int.class, int.class);
            mMouseWheel = browser.getMethod("sendMouseWheel", int.class, int.class,
                    double.class, int.class);
            mKeyPress = browser.getMethod("sendKeyPress", int.class, long.class, int.class);
            mKeyRelease = browser.getMethod("sendKeyRelease", int.class, long.class, int.class);
            mKeyTyped = browser.getMethod("sendKeyTyped", char.class, int.class);

            state = State.READY;
            LOGGER.info("[ndidisplays] MCEF found — web terminals enabled");
            return true;
        } catch (ReflectiveOperationException | LinkageError absent) {
            state = State.ABSENT;
            LOGGER.info("[ndidisplays] MCEF not present ({}); web terminals will show an"
                    + " offline screen", absent.toString());
            return false;
        }
    }

    /** True when MCEF is installed and its Chromium runtime has finished initialising. */
    public static boolean available() {
        if (!resolve()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(mIsInitialized.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            state = State.ABSENT;
            LOGGER.warn("[ndidisplays] MCEF availability check failed: {}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The browser for a terminal, created on first use and reconfigured in place when the
     * terminal's URL or resolution changes.
     *
     * @return the session, or null when MCEF is unavailable or the browser could not be made
     */
    public static Session session(BlockPos pos, String url, int width, int height, long gameTime) {
        if (url == null || url.isBlank() || !available()) {
            return null;
        }
        BlockPos key = pos.immutable();
        Session existing = SESSIONS.get(key);
        if (existing != null) {
            existing.lastUsed = gameTime;
            if (existing.width != width || existing.height != height) {
                existing.width = width;
                existing.height = height;
                invoke(mResize, existing.browser, width, height);
            }
            if (!url.equals(existing.url)) {
                existing.url = url;
                invoke(mLoadUrl, existing.browser, url);
            }
            return existing;
        }
        if (SESSIONS.size() >= MAX_BROWSERS) {
            retireOldest();
        }
        try {
            // Not transparent: a page with no background would otherwise composite against
            // whatever is behind the screen, which reads as a bug rather than a feature.
            Object browser = mCreateBrowser.invoke(null, url, false, width, height);
            if (browser == null) {
                return null;
            }
            Session made = new Session(browser, url, width, height);
            made.lastUsed = gameTime;
            SESSIONS.put(key, made);
            LOGGER.info("[ndidisplays] web terminal at {} opened {} ({}x{})", key, url, width, height);
            return made;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            state = State.ABSENT;
            LOGGER.warn("[ndidisplays] could not create a browser for the terminal at {}: {}",
                    key, e.toString(), e);
            return null;
        }
    }

    /** The existing session for a terminal without creating one. */
    public static Session peek(BlockPos pos) {
        return SESSIONS.get(pos.immutable());
    }

    public static void close(BlockPos pos) {
        Session s = SESSIONS.remove(pos.immutable());
        if (s != null) {
            invoke(mClose, s.browser);
            restoreMouseGrab();
        }
    }

    /** Called when leaving a world: Chromium processes must not outlive the level. */
    public static void closeAll() {
        for (Iterator<Map.Entry<BlockPos, Session>> it = SESSIONS.entrySet().iterator();
                it.hasNext(); ) {
            invoke(mClose, it.next().getValue().browser);
            it.remove();
        }
        restoreMouseGrab();
    }

    /**
     * Undoes MCEF's cursor damage after a browser closes.
     *
     * MCEF applies Chromium cursor changes by calling glfwSetInputMode directly on Minecraft's
     * window, and tearing a page down fires one last cursor change — so breaking a web terminal
     * left the player's mouse visibly ungrabbed mid-game. Vanilla's MouseHandler still believes
     * the mouse is grabbed, so grabMouse() alone is a no-op; it has to be released first. Queued
     * through Minecraft.tell so it runs after MCEF's own deferred close work, and only re-grabs
     * when no screen is open — closing a terminal from a GUI must not steal the cursor.
     */
    private static void restoreMouseGrab() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.tell(() -> {
            if (mc.screen == null && mc.isWindowActive() && mc.player != null) {
                mc.mouseHandler.releaseMouse();
                mc.mouseHandler.grabMouse();
            }
        });
    }

    private static void retireOldest() {
        BlockPos oldest = null;
        long best = Long.MAX_VALUE;
        for (Map.Entry<BlockPos, Session> e : SESSIONS.entrySet()) {
            if (e.getValue().lastUsed < best) {
                best = e.getValue().lastUsed;
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            LOGGER.info("[ndidisplays] retiring the idle web terminal at {} (browser cap {})",
                    oldest, MAX_BROWSERS);
            close(oldest);
        }
    }

    // ------------------------------------------------------------------ input

    public static void mouseMove(Session s, int x, int y) {
        invoke(mMouseMove, s.browser, x, y);
    }

    /** {@code button}: 0 left, 1 middle, 2 right — CEF's ordering. */
    public static void mouseDown(Session s, int x, int y, int button) {
        invoke(mMousePress, s.browser, x, y, button);
    }

    public static void mouseUp(Session s, int x, int y, int button) {
        invoke(mMouseRelease, s.browser, x, y, button);
    }

    public static void scroll(Session s, int x, int y, double amount) {
        // CEF measures wheel deltas in pixels, not notches; a notch is conventionally ~40.
        invoke(mMouseWheel, s.browser, x, y, amount * 40.0, 0);
    }

    public static void keyDown(Session s, int keyCode, int modifiers) {
        invoke(mKeyPress, s.browser, keyCode, 0L, modifiers);
    }

    public static void keyUp(Session s, int keyCode, int modifiers) {
        invoke(mKeyRelease, s.browser, keyCode, 0L, modifiers);
    }

    public static void charTyped(Session s, char c, int modifiers) {
        invoke(mKeyTyped, s.browser, c, modifiers);
    }

    public static void reload(Session s) {
        invoke(mLoadUrl, s.browser, s.url);
    }

    /**
     * Every call into CEF goes through here. A browser failing must never take a frame down
     * with it, so the exception is logged once per kind and swallowed.
     */
    private static void invoke(Method m, Object target, Object... args) {
        if (m == null || target == null) {
            return;
        }
        try {
            m.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("[ndidisplays] MCEF call {} failed: {}", m.getName(), e.toString());
        }
    }
}
