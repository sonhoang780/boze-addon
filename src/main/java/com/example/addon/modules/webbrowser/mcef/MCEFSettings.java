package com.example.addon.modules.webbrowser.mcef;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/** Ported from com.cinemamod.mcef.MCEFSettings -- see CefUtil's class doc for why. */
public class MCEFSettings {
    private static final Path PATH =
        Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mcef").resolve("mcef.properties");
    private static int deleteRetries = 0;

    private boolean skipDownload = false;
    private String downloadMirror = "https://mcef-download.cinemamod.com";
    private String userAgent = null;
    private boolean useCache = true;

    public boolean isSkipDownload() { return skipDownload; }
    public void setSkipDownload(boolean skipDownload) { this.skipDownload = skipDownload; saveAsync(); }
    public String getDownloadMirror() { return downloadMirror; }
    public void setDownloadMirror(String downloadMirror) { this.downloadMirror = downloadMirror; saveAsync(); }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; saveAsync(); }
    public boolean isUsingCache() { return useCache; }
    public void setUseCache(boolean useCache) { this.useCache = useCache; saveAsync(); }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void save() throws IOException {
        File file = PATH.toFile();
        file.getParentFile().mkdirs();
        if (!file.exists()) file.createNewFile();
        Properties properties = new Properties();
        properties.setProperty("skip-download", String.valueOf(skipDownload));
        properties.setProperty("download-mirror", String.valueOf(downloadMirror));
        properties.setProperty("user-agent", String.valueOf(userAgent));
        properties.setProperty("use-cache", String.valueOf(useCache));
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }
    }

    public void load() throws IOException {
        File file = PATH.toFile();
        if (!file.exists()) {
            save();
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        try {
            skipDownload = Boolean.parseBoolean(properties.getProperty("skip-download"));
            downloadMirror = properties.getProperty("download-mirror");
            userAgent = properties.getProperty("user-agent");
            useCache = Boolean.parseBoolean(properties.getProperty("use-cache"));
        } catch (Exception e) {
            if (deleteRetries++ > 20) return;
            file.delete();
            save();
        }
    }
}
