package com.example.addon.modules.webbrowser.mcef;

import com.cinemamod.mcef.MCEFPlatform;
import net.minecraft.client.Minecraft;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Objects;

/**
 * Ported from com.cinemamod.mcef.CefUtil (package-private in the original jar) --
 * decompiled with CFR and re-mapped from Yarn intermediary (class_310 = Minecraft) to
 * this project's real Mojmap names. See build.gradle's MCEF dependency comment for why
 * the original jar can't be used directly (NoClassDefFoundError: class_310 at runtime).
 */
final class CefUtil {
    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;
    private static final Path CACHE_PATH =
        Minecraft.getInstance().gameDirectory.toPath().resolve("mods").resolve("mcef-cache");

    private CefUtil() {}

    private static void setUnixExecutable(File file) {
        HashSet<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        try {
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (IOException e) {
            MCEF.getLogger().error("Failed to set " + file + " as executable.", e);
        }
    }

    static boolean init() {
        MCEFPlatform platform = MCEFPlatform.getPlatform();
        if (platform.isLinux()) {
            File jcefHelperFile = new File(System.getProperty("mcef.libraries.path"), platform.getNormalizedName() + "/jcef_helper");
            setUnixExecutable(jcefHelperFile);
        } else if (platform.isMacOS()) {
            File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
            setUnixExecutable(new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper"));
            setUnixExecutable(new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"));
            setUnixExecutable(new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)"));
            setUnixExecutable(new File(mcefLibrariesPath, platform.getNormalizedName() + "/jcef_app.app/Contents/Frameworks/jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)"));
        }

        // TESTED 2026-07-11 (2nd time, removed then restored again): still doesn't fix
        // the modal-overlay bleed-through (confirmed happens on plain posts too, not just
        // ads) -- ruled out a second time, now under the transparent=false + full-frame-
        // replace code too. That bug is very likely CEF OSR's well-known inability to run
        // `backdrop-filter: blur()`/dim overlays (Facebook's modal backdrop almost
        // certainly uses one) -- backdrop-filter needs GPU-accelerated filter effects with
        // no software-raster fallback in Chromium, and windowless/OSR mode can't use that
        // GPU raster path at all regardless of this specific compositing flag. Same
        // category as the 30fps windowless cap below: a real jcef/CEF OSR limitation, not
        // fixable from this addon's code. Restored since disabling this only has downside
        // (the onAcceleratedPaint stall below) with no upside shown across two tests.
        // OSR (windowless) rendering caps its compositor at windowless_frame_rate (default
        // 30fps), which reads as choppy on scroll. This JCEF build exposes NO way to raise
        // it -- no CefBrowserSettings class, no N_SetWindowlessFrameRate native (verified
        // via javap on the mcef-keksuccino jar, including the current 2.2.0 release), and
        // "--off-screen-frame-rate" is not a real Chromium switch (silently ignored).
        // MCEFBrowser#invalidate() (CefBrowserHost::Invalidate) does NOT bypass this cap
        // either -- confirmed via CEF's own issue tracker, it only marks the view dirty,
        // actual paint dispatch still runs on CEF's internal compositor timer. This is a
        // hard limitation of the vendored jcef binary; no fix exists at this addon's level.
        String[] cefSwitches = { "--autoplay-policy=no-user-gesture-required", "--disable-web-security", "--enable-widevine-cdm", "--disable-gpu-compositing" };
        if (!CefApp.startup(cefSwitches)) return false;

        MCEFSettings settings = MCEF.getSettings();
        CefSettings cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        if (settings.isUsingCache()) {
            cefSettings.cache_path = CACHE_PATH.toAbsolutePath().toString();
        }
        // log_file forces CEF's own native-side log (renderer/GPU process crashes, sandbox
        // failures, etc.) into a readable file instead of nowhere, since none of that
        // surfaces through our Java-side logger. log_severity was VERBOSE while diagnosing
        // an earlier "onPaint never fires with no error anywhere" black-box issue (now
        // resolved) -- VERBOSE floods stdout with routine internal noise (e.g. every
        // MSPL::OnSpeedLimitChange from media_stream_manager.cc), so it's ERROR now;
        // bump back to VERBOSE only while actively debugging a real black-box CEF failure.
        cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        cefSettings.log_file = CACHE_PATH.resolveSibling("mcef-cef.log").toAbsolutePath().toString();
        // ColorType's ctor packs params as (A,R,G,B) (confirmed via javap: a<<24|r<<16|g<<8|b)
        // -- this was alpha=0 (fully transparent), which sounds right for a browser meant to
        // float over the world, but it's the CANVAS CLEAR color: any page region that relies
        // on the plain <html>/<body> background (no element of its own painting over it --
        // common for whole gutters/gaps between styled cards) shows THIS color instead, so a
        // real page's own solid dark-mode background silently became a hole straight through
        // to the Minecraft world behind it. Opaque black instead -- real content still paints
        // over it as soon as it loads either way, so this only ever shows as a a brief flash
        // before first paint, not a permanent see-through gap in an already-loaded page.
        cefSettings.background_color = cefSettings.new ColorType(255, 0, 0, 0);
        if (!Objects.equals(settings.getUserAgent(), "null")) {
            cefSettings.user_agent = settings.getUserAgent();
        } else {
            cefSettings.user_agent_product = "MCEF/2";
        }

        // CefApp is a JVM-wide singleton (org.cef.CefApp.self, a static field) that
        // outlives our own init/close cycle -- CefUtil.shutdown() disposes the CEF
        // client/app but never resets that static field. If an earlier attempt in
        // this same game session got far enough to move CefApp's internal state
        // past NEW (e.g. WebBrowser was toggled off/on again after a failure, or a
        // saved "enabled" state auto-restored on load and a later manual retoggle
        // raced it), CefApp.getInstance(args, settings) throws
        // IllegalStateException("Settings can only be passed to CEF before
        // createClient is called the first time.") -- settings are ONLY accepted
        // on the very first call ever, verified by decompiling CefApp.getInstance's
        // own guard. Detect that case and just fetch the existing instance instead
        // of re-passing settings.
        CefApp.CefAppState state = CefApp.getState();
        if (state == CefApp.CefAppState.NONE || state == CefApp.CefAppState.NEW) {
            cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
        } else {
            MCEF.getLogger().warn("CefApp already past NEW state (" + state + ") -- reusing existing instance, settings ignored.");
            cefAppInstance = CefApp.getInstance();
        }
        cefClientInstance = cefAppInstance.createClient();
        init = true;
        return true;
    }

    static void shutdown() {
        if (isInit()) {
            init = false;
            cefClientInstance.dispose();
            cefAppInstance.dispose();
        }
    }

    static boolean isInit() { return init; }
    static CefApp getCefApp() { return cefAppInstance; }
    static CefClient getCefClient() { return cefClientInstance; }
}
