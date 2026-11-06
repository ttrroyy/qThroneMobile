package moe.matsuri.nb4a.utils;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.webkit.WebView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;

import app.rawline.mobile.BuildConfig;
import app.rawline.mobile.ktx.Logs;

public class JavaUtil {

    // Webview Utils

    public static void handleWebviewDir(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        try {
            Set<String> pathSet = new HashSet<>();
            String suffix;
            String dataPath = context.getDataDir().getAbsolutePath();
            String webViewDir = "/app_webview";
            String huaweiWebViewDir = "/app_hws_webview";
            String lockFile = "/webview_data.lock";
            String processName = Application.getProcessName();
            if (!BuildConfig.APPLICATION_ID.equals(processName)) { // not the main process
                suffix = TextUtils.isEmpty(processName) ? context.getPackageName() : processName;
                WebView.setDataDirectorySuffix(suffix);
                suffix = "_" + suffix;
                pathSet.add(dataPath + webViewDir + suffix + lockFile);
                if (checkIsHuaweiRom()) {
                    pathSet.add(dataPath + huaweiWebViewDir + suffix + lockFile);
                }
            } else {
                // main process
                suffix = "_" + processName;
                pathSet.add(dataPath + webViewDir + lockFile); // without the process-name suffix (default)
                pathSet.add(dataPath + webViewDir + suffix + lockFile); // with the suffix the system adds
                if (checkIsHuaweiRom()) { // some Huawei phones rename the WebView directory
                    pathSet.add(dataPath + huaweiWebViewDir + lockFile);
                    pathSet.add(dataPath + huaweiWebViewDir + suffix + lockFile);
                }
            }
            for (String path : pathSet) {
                File file = new File(path);
                if (file.exists()) {
                    tryLockOrRecreateFile(file);
                    break;
                }
            }
        } catch (Exception e) {
            Logs.INSTANCE.e(e);
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void tryLockOrRecreateFile(File file) {
        try {
            FileLock tryLock = new RandomAccessFile(file, "rw").getChannel().tryLock();
            if (tryLock != null) {
                tryLock.close();
            } else {
                createFile(file, file.delete());
            }
        } catch (Exception e) {
            e.printStackTrace();
            boolean deleted = false;
            if (file.exists()) {
                deleted = file.delete();
            }
            createFile(file, deleted);
        }
    }

    private static void createFile(File file, boolean deleted) {
        try {
            if (deleted && !file.exists()) {
                file.createNewFile();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean checkIsHuaweiRom() {
        return Build.MANUFACTURER.contains("HUAWEI");
    }

    @SuppressLint("PrivateApi")
    public static String getProcessName() {
        if (Build.VERSION.SDK_INT >= 28)
            return Application.getProcessName();

        // Using the same technique as Application.getProcessName() for older devices
        // Using reflection since ActivityThread is an internal API

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            String methodName = "currentProcessName";
            Method getProcessName = activityThread.getDeclaredMethod(methodName);
            return (String) getProcessName.invoke(null);
        } catch (Exception e) {
            return BuildConfig.APPLICATION_ID;
        }
    }

    // gson

    public static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .setLenient()
            .disableHtmlEscaping()
            .create();

}
