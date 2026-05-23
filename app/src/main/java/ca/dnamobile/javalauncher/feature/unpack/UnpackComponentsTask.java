/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.feature.unpack;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.utils.path.PathManager;

public class UnpackComponentsTask extends AbstractUnpackTask {
    private final Context context;
    private final Components component;
    private AssetManager assetManager;
    private String rootDir;
    private File versionFile;
    private boolean checkFailed;

    public UnpackComponentsTask(@NonNull Context context, @NonNull Components component) {
        this.context = context.getApplicationContext();
        this.component = component;

        try {
            assetManager = this.context.getAssets();
            rootDir = resolveRootDir(component);
            versionFile = new File(rootDir, component.component + "/version");

            // Validate that this component actually exists in assets before adding it to the install list.
            try (InputStream ignored = assetManager.open("components/" + component.component + "/version")) {
                // Just checking the file exists.
            }
        } catch (Throwable throwable) {
            checkFailed = true;
            Logging.e("UnpackComponentsTask", "Component check failed for " + component.component, throwable);
        }
    }

    public boolean isCheckFailed() {
        return checkFailed;
    }

    @Override
    public boolean isNeedUnpack() {
        if (checkFailed) return false;

        if (!versionFile.exists()) {
            requestEmptyParentDir(versionFile);
            Logging.i("UnpackComponents", component.component + ": pack missing, installing...");
            return true;
        }

        try (InputStream assetVersion = assetManager.open("components/" + component.component + "/version");
             InputStream installedVersion = new FileInputStream(versionFile)) {
            String bundled = Tools.read(assetVersion);
            String installed = Tools.read(installedVersion);
            if (!bundled.equals(installed)) {
                requestEmptyParentDir(versionFile);
                Logging.i("UnpackComponents", component.component + ": version changed, reinstalling...");
                return true;
            }

            Logging.i("UnpackComponents", component.component + ": pack is up to date.");
            return false;
        } catch (Throwable throwable) {
            Logging.e("UnpackComponents", "Failed to compare component version for " + component.component, throwable);
            requestEmptyParentDir(versionFile);
            return true;
        }
    }

    @Override
    public void run() {
        if (listener != null) listener.onTaskStart();
        try {
            copyAssetDirectoryRecursively(
                    "components/" + component.component,
                    new File(rootDir, component.component).getAbsolutePath()
            );
        } catch (Throwable throwable) {
            Logging.e("UnpackComponents", "Failed to unpack " + component.component, throwable);
            throw new RuntimeException("Failed to unpack " + component.displayName, throwable);
        } finally {
            if (listener != null) listener.onTaskEnd();
        }
    }

    private static String resolveRootDir(@NonNull Components component) {
        switch (component) {
            case COMPONENTS:
            case WEBRTC_BRIDGE:
                return PathManager.DIR_DATA;

            case LWJGL3:
            case LWJGL341:
            case OTHER_LOGIN:
            case CACIOCAVALLO:
            case CACIOCAVALLO17:
                return PathManager.DIR_FILE.getAbsolutePath();

            default:
                return component.privateDirectory
                        ? PathManager.DIR_FILE.getAbsolutePath()
                        : PathManager.DIR_GAME_HOME;
        }
    }

    private void copyAssetDirectoryRecursively(@NonNull String assetPath, @NonNull String outputPath) throws IOException {
        String[] entries = assetManager.list(assetPath);
        if (entries == null) return;

        if (entries.length == 0) {
            File outFile = new File(outputPath);
            File parent = outFile.getParentFile();
            if (parent == null) return;
            Tools.copyAssetFile(context, assetPath, parent.getAbsolutePath(), outFile.getName(), true);
            return;
        }

        File outputDir = new File(outputPath);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create directory: " + outputDir.getAbsolutePath());
        }

        for (String entry : entries) {
            String childAssetPath = assetPath + "/" + entry;
            String childOutputPath = outputPath + File.separator + entry;
            copyAssetDirectoryRecursively(childAssetPath, childOutputPath);
        }
    }

    private void requestEmptyParentDir(@NonNull File file) {
        File parent = file.getParentFile();
        if (parent == null) return;
        if (parent.exists() && parent.isDirectory()) {
            PathManager.deleteQuietly(parent);
        }
        //noinspection ResultOfMethodCallIgnored
        parent.mkdirs();
    }
}
