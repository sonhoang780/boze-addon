package com.example.addon.modules.webbrowser;

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.example.addon.modules.webbrowser.mcef.MCEF;
import com.example.addon.modules.webbrowser.mcef.MCEFBrowser;
import com.example.addon.screens.WebBrowserScreen;
import dev.boze.api.utility.ChatHelper;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the open {@link MCEFBrowser} tabs so WebBrowser (HUD tile) and WebBrowserScreen
 * (interactive overlay) always agree on which tab is active -- see
 * docs/superpowers/specs/2026-07-10-webbrowser-multitab-design.md (multi-tab follow-up to
 * the original single-browser design in 2026-07-10-web-browser-design.md).
 *
 * Each tab is a genuinely separate {@link MCEFBrowser} (its own Chromium renderer process),
 * not a fake single-page tab list -- one tab crashing/hanging doesn't affect the others.
 *
 * Uses the VENDORED com.example.addon.modules.webbrowser.mcef.MCEF/MCEFBrowser (ported,
 * remapped to real Mojmap names), NOT com.cinemamod.mcef.MCEF -- the original jar's glue
 * classes are compiled against Yarn intermediary names and throw NoClassDefFoundError on
 * this project's real-Mojmap 26.1.2 runtime. See MCEF.java's class doc for the full story,
 * including why the native JCEF download step (normally done by a mixin we don't load)
 * had to be ported too.
 *
 * CefApp/CefClient live for the whole game process once initialized -- close() only closes
 * this addon's own tabs, never MCEF.shutdown() (see close()'s own doc: JCEF's CefApp cannot
 * be re-initialized after dispose, so tearing it down on every module disable broke
 * re-enabling within the same session with "CefApp was terminated").
 */
public class WebBrowserManager {

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;
    private static final String DEFAULT_URL_KEY = "webbrowser_default_url";
    private static final String DEFAULT_URL_FALLBACK = "https://www.google.com";

    private static final class BrowserTab {
        final MCEFBrowser browser;
        final String title; // the URL it was opened with; not live-updated (MCEF's
                             // title-change callback isn't wired up -- a static label
                             // is enough for the tab strip, not worth the extra plumbing)

        BrowserTab(MCEFBrowser browser, String title) {
            this.browser = browser;
            this.title = title;
        }
    }

    private static final List<BrowserTab> tabs = new ArrayList<>();
    private static int activeTab = -1;
    private static boolean initializing = false;
    private static boolean unsupported = false;

    private WebBrowserManager() {}

    public static void init() {
        if (!tabs.isEmpty() || initializing) return;

        // CefApp/CefClient are a JVM-wide singleton meant to be created ONCE per
        // process and torn down ONLY at real process exit (MCEF.initialize() already
        // registers a JVM shutdown hook for that -- see MCEF.java). If this addon
        // already initialized CEF earlier in this same game session (a previous
        // WebBrowser enable), skip the whole download/CefApp.startup/getInstance
        // pipeline and just open a new browser against the still-alive CefClient --
        // re-running init() after close() previously called MCEF.shutdown() (which
        // disposes CefApp permanently) is what caused "CefApp was terminated" on a
        // second enable in the same session. See close()'s doc for the other half
        // of this fix.
        if (MCEF.isInitialized()) {
            try {
                openTab(getDefaultUrl());
            } catch (Throwable t) {
                unsupported = true;
                MCEF.getLogger().error("WebBrowser: createBrowser failed (already-initialized path)", t);
                ChatHelper.sendMsg("WebBrowser", "§ccreateBrowser failed: " + t + " -- see latest.log.");
            }
            return;
        }

        initializing = true;
        ChatHelper.sendMsg("WebBrowser", "§7Initializing CEF (first run may download ~100+MB, can take a while)...");

        MCEF.scheduleForInit(success -> Minecraft.getInstance().execute(() -> {
            ChatHelper.sendMsg("WebBrowser", "§7scheduleForInit callback fired: success=" + success);
            if (!success) {
                unsupported = true;
                initializing = false;
                ChatHelper.sendMsg("WebBrowser", "§cFailed to initialize CEF -- see latest.log.");
                return;
            }
            try {
                openTab(getDefaultUrl());
            } catch (Throwable t) {
                unsupported = true;
                MCEF.getLogger().error("WebBrowser: createBrowser failed", t);
                ChatHelper.sendMsg("WebBrowser", "§ccreateBrowser failed: " + t + " -- see latest.log.");
            } finally {
                initializing = false;
            }
        }));

        // Download (100+MB) off-thread to avoid freezing render. CEF itself MUST
        // start on the main thread: N_DoMessageLoopWork() pumps from the Render thread
        // every frame, and CEF's message loop deadlocks if started on a different thread.
        Thread initThread = new Thread(() -> {
            try {
                MCEF.setupLibraryPathAndDownload();
                Minecraft.getInstance().execute(() -> {
                    try {
                        boolean ok = MCEF.initialize();
                        MCEF.getLogger().info("WebBrowser: MCEF.initialize() returned " + ok);
                    } catch (Throwable t) {
                        MCEF.getLogger().error("WebBrowser: MCEF.initialize() threw", t);
                        unsupported = true;
                        initializing = false;
                        ChatHelper.sendMsg("WebBrowser", "§cCEF init threw: " + t + " -- see latest.log.");
                    }
                });
            } catch (Throwable t) {
                MCEF.getLogger().error("WebBrowser: download threw", t);
                Minecraft.getInstance().execute(() -> {
                    unsupported = true;
                    initializing = false;
                    ChatHelper.sendMsg("WebBrowser", "§cCEF download threw: " + t + " -- see latest.log.");
                });
            }
        }, "WebBrowser-MCEF-Init");
        initThread.setDaemon(true);
        initThread.start();
    }

    /** Opens a new tab (a new MCEFBrowser/Chromium process), makes it active. Throws on browser-creation failure -- caller decides how to surface it. */
    public static void openTab(String url) {
        // transparent=false: transparent OSR mode told Chromium to composite the page on a
        // fully transparent backdrop, which (a) made any region relying on the default
        // <body> background render alpha-0 (Facebook's feed gutters showed the Minecraft
        // world straight through) and (b) left ghost trails when content reflowed, since
        // nothing opaque ever painted over the old pixels (Instagram comment rows stacking
        // on top of each other after their like-counts loaded in). It also silently
        // overrides cefSettings.background_color, which is why fixing that alone did
        // nothing. The tile/screen draw the texture over their own opaque chrome anyway,
        // so nothing visual is lost by opting out.
        MCEFBrowser b = MCEF.createBrowser(url, false, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        MCEFCursorChangeListener original = b.getCursorChangeListener();
        b.setCursorChangeListener(cefCursorId -> {
            if (Minecraft.getInstance().screen instanceof WebBrowserScreen) {
                original.onCursorChange(cefCursorId);
            }
        });
        tabs.add(new BrowserTab(b, url));
        activeTab = tabs.size() - 1;
        ChatHelper.sendMsg("WebBrowser", "§aTab opened, loading " + url);
    }

    /** Closes and removes the tab at index. Adjusts activeTab to a neighbor if others remain. Caller must handle the now-zero-tabs case (WebBrowserScreen closes itself). */
    public static void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        BrowserTab removed = tabs.remove(index);
        try {
            removed.browser.close();
        } catch (Throwable t) {
            MCEF.getLogger().error("WebBrowser: tab close threw", t);
        }
        if (tabs.isEmpty()) {
            activeTab = -1;
            return;
        }
        activeTab = Math.min(index, tabs.size() - 1);
    }

    public static void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTab = index;
    }

    public static int getActiveTabIndex() { return activeTab; }
    public static int getTabCount() { return tabs.size(); }

    public static String getTabTitle(int index) {
        if (index < 0 || index >= tabs.size()) return "";
        return tabs.get(index).title;
    }

    public static MCEFBrowser getTabBrowser(int index) {
        if (index < 0 || index >= tabs.size()) return null;
        return tabs.get(index).browser;
    }

    public static MCEFBrowser getActiveBrowser() {
        if (activeTab < 0 || activeTab >= tabs.size()) return null;
        return tabs.get(activeTab).browser;
    }

    public static void close() {
        // Only close this addon's own tabs, not CefApp/CefClient -- see init()'s doc for
        // why MCEF.shutdown() here was wrong (JCEF's CefApp cannot be re-initialized once
        // disposed; it's meant to live for the whole process, torn down only by the JVM
        // shutdown hook MCEF.initialize() already registers). Leaving CEF resident means
        // jcef_helper.exe is expected to still be running after this returns, so the
        // lingering-process cleanup no longer applies here -- that's only relevant at real
        // process exit, which the shutdown hook covers.
        for (BrowserTab tab : tabs) {
            try {
                tab.browser.close();
            } catch (Throwable t) {
                MCEF.getLogger().error("WebBrowser: tab close threw during shutdown", t);
            }
        }
        tabs.clear();
        activeTab = -1;
    }

    public static boolean isUnsupported() {
        return unsupported;
    }

    public static boolean isReady() {
        return !tabs.isEmpty();
    }

    public static String getDefaultUrl() {
        String saved = com.example.addon.AddonConfig.get(DEFAULT_URL_KEY, "");
        return saved.isEmpty() ? DEFAULT_URL_FALLBACK : saved;
    }

    public static void setDefaultUrl(String url) {
        com.example.addon.AddonConfig.set(DEFAULT_URL_KEY, url);
    }
}
