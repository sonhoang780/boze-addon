package com.example.addon.modules.webbrowser.mcef;

import com.cinemamod.mcef.MCEFApp;
import com.cinemamod.mcef.MCEFClient;
import com.cinemamod.mcef.MCEFDownloader;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.ModScheme;
import com.cinemamod.mcef.listeners.MCEFInitListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.FileUtils;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Ported entrypoint from com.cinemamod.mcef.MCEF -- see CefUtil's class doc for why.
 *
 * setupLibraryPathAndDownload() replaces what the original jar's Fabric mixin
 * (CefDownloadMixin, injected at some vanilla class's <clinit>) did automatically on
 * game boot: setting the "mcef.libraries.path"/"jcef.path" system properties JCEF's
 * native loader needs, and downloading+extracting the actual JCEF native binaries if
 * missing. We don't load that mixin at all (no mixin config wires it up here), so this
 * MUST run before initialize() or CefApp.startup() fails with no native library found --
 * this was the actual root cause under the original NoClassDefFoundError crash, not just
 * the class-name mismatch. Call this once, synchronously, off the main thread (it blocks
 * on a first-run download), before calling initialize().
 */
public final class MCEF {
    private static final Logger LOGGER = LoggerFactory.getLogger("MCEF");
    private static MCEFSettings settings;
    private static MCEFApp app;
    private static MCEFClient client;
    private static final ArrayList<MCEFInitListener> awaitingInit = new ArrayList<>();
    private static final HashMap<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new HashMap<>();
    private static volatile boolean librariesReady = false;

    private MCEF() {}

    public static void scheduleForInit(MCEFInitListener task) {
        awaitingInit.add(task);
    }

    public static Logger getLogger() { return LOGGER; }

    public static MCEFSettings getSettings() {
        if (settings == null) {
            settings = new MCEFSettings();
            try {
                settings.load();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return settings;
    }

    /** Must be called once, off the main thread, before initialize(). See class doc. */
    public static void setupLibraryPathAndDownload() throws IOException {
        if (librariesReady) return;
        File mcefLibrariesDir = new File(FabricLoader.getInstance().getGameDir().toFile(), "mods/mcef-libraries/");
        mcefLibrariesDir.mkdirs();
        System.setProperty("mcef.libraries.path", mcefLibrariesDir.getCanonicalPath());
        System.setProperty("jcef.path", new File(mcefLibrariesDir, MCEFPlatform.getPlatform().getNormalizedName()).getCanonicalPath());

        String javaCefCommit = getJavaCefCommit();
        getLogger().info("java-cef commit: " + javaCefCommit);
        MCEFSettings settings = getSettings();
        MCEFDownloader downloader = new MCEFDownloader(settings.getDownloadMirror(), javaCefCommit, MCEFPlatform.getPlatform());

        boolean downloadJcefBuild = !downloader.downloadJavaCefChecksum();
        downloadJcefBuild |= !mcefLibrariesDir.exists();
        if (downloadJcefBuild && !settings.isSkipDownload()) {
            downloader.downloadJavaCefBuild();
            downloader.extractJavaCefBuild(true);
        }
        librariesReady = true;
    }

    public static boolean initialize() {
        getLogger().info("Initializing CEF on " + MCEFPlatform.getPlatform().getNormalizedName() + "...");
        if (CefUtil.init()) {
            app = new MCEFApp(CefUtil.getCefApp());
            client = new MCEFClient(CefUtil.getCefClient());
            awaitingInit.forEach(t -> t.onInit(true));
            awaitingInit.clear();
            getLogger().info("Chromium Embedded Framework initialized");
            app.getHandle().registerSchemeHandlerFactory("mod", "", (browser, frame, url, request) -> new ModScheme(request.getURL()));
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            if (platform.isLinux() || platform.isWindows()) {
                Runtime.getRuntime().addShutdownHook(new Thread(MCEF::shutdown, "MCEF-Shutdown"));
            } else if (platform.isMacOS()) {
                CefUtil.getCefApp().macOSTerminationRequestRunnable = () -> {
                    shutdown();
                    Minecraft.getInstance().stop();
                };
            }
            return true;
        }
        awaitingInit.forEach(t -> t.onInit(false));
        awaitingInit.clear();
        getLogger().error("Could not initialize Chromium Embedded Framework");
        shutdown();
        return false;
    }

    public static MCEFApp getApp() { assertInitialized(); return app; }
    public static MCEFClient getClient() { assertInitialized(); return client; }

    public static MCEFBrowser createBrowser(String url, boolean transparent) {
        assertInitialized();
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        return browser;
    }

    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        assertInitialized();
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        browser.resize(width, height);
        return browser;
    }

    public static boolean isInitialized() { return client != null; }

    public static void shutdown() {
        if (isInitialized()) {
            CefUtil.shutdown();
            client = null;
            app = null;
        }
        killLingeringHelperProcess();
    }

    // jcef_helper.exe doesn't always die on its own when the JVM exits abruptly (same
    // issue Soar's JCefBrowser.close() worked around) -- only relevant here, at real
    // process shutdown (the JVM shutdown hook registered in initialize()), NOT on every
    // WebBrowser module disable anymore (that used to call this and broke re-enabling
    // within the same session -- see WebBrowserManager.close()'s doc).
    private static void killLingeringHelperProcess() {
        if (!MCEFPlatform.getPlatform().isWindows()) return;
        String processName = "jcef_helper.exe";
        try {
            Process process = new ProcessBuilder("tasklist").start();
            boolean isRunning = false;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(processName)) {
                        isRunning = true;
                        break;
                    }
                }
            }
            if (isRunning) {
                new ProcessBuilder("taskkill", "/F", "/IM", processName).start();
            }
        } catch (Exception e) {
            getLogger().error("Unable to check/kill lingering " + processName, e);
        }
    }

    private static void assertInitialized() {
        if (!isInitialized()) throw new RuntimeException("Chromium Embedded Framework was never initialized.");
    }

    /** Same MANIFEST.MF-scan fallback the original used -- mods/libraries ship their java-cef commit there. */
    public static String getJavaCefCommit() throws IOException {
        if (System.getProperty("mcef.java.cef.commit") != null) {
            return System.getProperty("mcef.java.cef.commit");
        }
        var resources = MCEF.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
        HashMap<String, String> commits = new HashMap<>(1);
        resources.asIterator().forEachRemaining(resource -> {
            java.util.Properties properties = new java.util.Properties();
            try {
                properties.load(resource.openStream());
                if (properties.containsKey("java-cef-commit")) {
                    commits.put(resource.getFile(), properties.getProperty("java-cef-commit"));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        if (!commits.isEmpty()) {
            return commits.get(commits.keySet().stream().toList().get(0));
        }
        return null;
    }

    static long getGLFWCursorHandle(CefCursorType cursorType) {
        if (CEF_TO_GLFW_CURSORS.containsKey(cursorType)) {
            return CEF_TO_GLFW_CURSORS.get(cursorType);
        }
        long glfwCursorHandle = GLFW.glfwCreateStandardCursor(cursorType.glfwId);
        CEF_TO_GLFW_CURSORS.put(cursorType, glfwCursorHandle);
        return glfwCursorHandle;
    }
}
