package com.hjq.permissions;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.xmlpull.v1.XmlPullParserException;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/XXPermissions
 *    time   : 2018/06/15
 *    desc   : 权限相关工具类
 */
final class PermissionUtils {

    /** Handler 对象 */
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 判断某个危险权限是否授予了
     */
    @RequiresApi(api = AndroidVersion.ANDROID_6)
    static boolean checkSelfPermission(@NonNull Context context, @NonNull String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("ConstantConditions")
    @RequiresApi(AndroidVersion.ANDROID_4_4)
    static boolean checkOpNoThrow(Context context, String opFieldName, int opDefaultValue) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        ApplicationInfo appInfo = context.getApplicationInfo();
        String pkg = context.getApplicationContext().getPackageName();
        int uid = appInfo.uid;
        try {
            Class<?> appOpsClass = Class.forName(AppOpsManager.class.getName());
            int opValue;
            try {
                Field opValueField = appOpsClass.getDeclaredField(opFieldName);
                opValue = (int) opValueField.get(Integer.class);
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
                opValue = opDefaultValue;
            }
            Method checkOpNoThrowMethod = appOpsClass.getMethod("checkOpNoThrow", Integer.TYPE,
                    Integer.TYPE, String.class);
            return ((int) checkOpNoThrowMethod.invoke(appOps, opValue, uid, pkg)
                    == AppOpsManager.MODE_ALLOWED);
        } catch (ClassNotFoundException | NoSuchMethodException |
                InvocationTargetException | IllegalAccessException | RuntimeException e) {
            return true;
        }
    }

    @RequiresApi(AndroidVersion.ANDROID_4_4)
    static boolean checkOpNoThrow(Context context, String opName) {
        AppOpsManager appOps = (AppOpsManager)
                context.getSystemService(Context.APP_OPS_SERVICE);
        int mode;
        if (AndroidVersion.isAndroid10()) {
            mode = appOps.unsafeCheckOpNoThrow(opName,
                    context.getApplicationInfo().uid, context.getPackageName());
        } else {
            mode = appOps.checkOpNoThrow(opName,
                    context.getApplicationInfo().uid, context.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 解决 Android 12 调用 shouldShowRequestPermissionRationale 出现内存泄漏的问题
     * Android 12L 和 Android 13 版本经过测试不会出现这个问题，证明 Google 在新版本上已经修复了这个问题
     * 但是对于 Android 12 仍是一个历史遗留问题，这是我们所有应用开发者不得不面对的一个事情
     *
     * issues 地址：https://github.com/getActivity/XXPermissions/issues/133
     */
    @RequiresApi(api = AndroidVersion.ANDROID_6)
    @SuppressWarnings({"JavaReflectionMemberAccess", "ConstantConditions", "BooleanMethodIsAlwaysInverted"})
    static boolean shouldShowRequestPermissionRationale(@NonNull Activity activity, @NonNull String permission) {
        if (AndroidVersion.getAndroidVersionCode() == AndroidVersion.ANDROID_12) {
            try {
                PackageManager packageManager = activity.getApplication().getPackageManager();
                Method method = PackageManager.class.getMethod("shouldShowRequestPermissionRationale", String.class);
                return (boolean) method.invoke(packageManager, permission);
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return activity.shouldShowRequestPermissionRationale(permission);
    }

    /**
     * 延迟一段时间执行 OnActivityResult，避免有些机型明明授权了，但还是回调失败的问题
     */
    static void postActivityResult(@NonNull List<String> permissions, @NonNull Runnable runnable) {
        long delayMillis;
        if (AndroidVersion.isAndroid11()) {
            delayMillis = 200;
        } else {
            delayMillis = 300;
        }

        if (PhoneRomUtils.isEmui() || PhoneRomUtils.isHarmonyOs()) {
            // 需要加长时间等待，不然某些华为机型授权了但是获取不到权限
            if (AndroidVersion.isAndroid8()) {
                delayMillis = 300;
            } else {
                delayMillis = 500;
            }
        } else if (PhoneRomUtils.isMiui()) {
            // 经过测试，发现小米 Android 11 及以上的版本，申请这个权限需要 1 秒钟才能判断到
            // 因为在 Android 10 的时候，这个特殊权限弹出的页面小米还是用谷歌原生的
            // 然而在 Android 11 之后的，这个权限页面被小米改成了自己定制化的页面
            // 测试了原生的模拟器和 vivo 云测并发现没有这个问题，所以断定这个 Bug 就是小米特有的
            if (AndroidVersion.isAndroid11() &&
                    PermissionUtils.containsPermission(permissions, Permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
                delayMillis = 1000;
            }
        }
        postDelayed(runnable, delayMillis);
    }

    /**
     * 延迟一段时间执行
     */
    static void postDelayed(@NonNull Runnable runnable, long delayMillis) {
        HANDLER.postDelayed(runnable, delayMillis);
    }

    /**
     * 当前是否处于 debug 模式
     */
    static boolean isDebugMode(@NonNull Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    @Nullable
    static AndroidManifestInfo getAndroidManifestInfo(Context context) {
        int apkPathCookie = PermissionUtils.findApkPathCookie(context, context.getApplicationInfo().sourceDir);
        // 如果 cookie 为 0，证明获取失败
        if (apkPathCookie == 0) {
            return null;
        }

        AndroidManifestInfo androidManifestInfo = null;
        try {
            androidManifestInfo = AndroidManifestParser.parseAndroidManifest(context, apkPathCookie);
            // 如果读取到的包名和当前应用的包名不是同一个的话，证明这个清单文件的内容不是当前应用的
            // 具体案例：https://github.com/getActivity/XXPermissions/issues/102
            if (!TextUtils.equals(context.getPackageName(),
                    androidManifestInfo.packageName)) {
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        }

        return androidManifestInfo;
    }

    /**
     * 优化权限回调结果
     */
    static void optimizePermissionResults(Activity activity, @NonNull String[] permissions, @NonNull int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {

            String permission = permissions[i];

            // 如果这个权限是特殊权限，则需要重新检查权限的状态
            if (PermissionApi.isSpecialPermission(permission)) {
                grantResults[i] = PermissionApi.getPermissionResult(activity, permission);
                continue;
            }

            // 如果是读取应用列表权限（国产权限），则需要重新检查权限的状态
            if (PermissionUtils.equalsPermission(permission, Permission.GET_INSTALLED_APPS)) {
                grantResults[i] = PermissionApi.getPermissionResult(activity, permission);
                continue;
            }

            // 如果是在 Android 14 上面，并且是图片权限或者视频权限，则需要重新检查权限的状态
            // 这是因为用户授权部分图片或者视频的时候，READ_MEDIA_VISUAL_USER_SELECTED 权限状态是授予的
            // 但是 READ_MEDIA_IMAGES 和 READ_MEDIA_VIDEO 的权限状态是拒绝的
            if (AndroidVersion.isAndroid14() &&
                (PermissionUtils.equalsPermission(permission, Permission.READ_MEDIA_IMAGES) ||
                PermissionUtils.equalsPermission(permission, Permission.READ_MEDIA_VIDEO))) {
                grantResults[i] = PermissionApi.getPermissionResult(activity, permission);
                continue;
            }

            if (AndroidVersion.isAndroid13() && AndroidVersion.getTargetSdkVersionCode(activity) >= AndroidVersion.ANDROID_13 &&
                PermissionUtils.equalsPermission(permission, Permission.WRITE_EXTERNAL_STORAGE)) {
                // 在 Android 13 不能申请 WRITE_EXTERNAL_STORAGE，会被系统直接拒绝，在这里需要重新检查权限的状态
                grantResults[i] = PermissionApi.getPermissionResult(activity, permission);
                continue;
            }

            if (Permission.getDangerPermissionFromAndroidVersion(permission) > AndroidVersion.getAndroidVersionCode()) {
                // 如果是申请了新权限，但却是旧设备上面运行的，会被系统直接拒绝，在这里需要重新检查权限的状态
                grantResults[i] = PermissionApi.getPermissionResult(activity, permission);
            }
        }
    }

    /**
     * 将数组转换成 ArrayList
     *
     * 这里解释一下为什么不用 Arrays.asList
     * 第一是返回的类型不是 java.util.ArrayList 而是 java.util.Arrays.ArrayList
     * 第二是返回的 ArrayList 对象是只读的，也就是不能添加任何元素，否则会抛异常
     */
    @SuppressWarnings("all")
    @NonNull
    static <T> ArrayList<T> asArrayList(@Nullable T... array) {
        int initialCapacity = 0;
        if (array != null) {
            initialCapacity = array.length;
        }
        ArrayList<T> list = new ArrayList<>(initialCapacity);
        if (array == null || array.length == 0) {
            return list;
        }
        for (T t : array) {
            list.add(t);
        }
        return list;
    }

    @SafeVarargs
    @NonNull
    static <T> ArrayList<T> asArrayLists(@Nullable T[]... arrays) {
        ArrayList<T> list = new ArrayList<>();
        if (arrays == null || arrays.length == 0) {
            return list;
        }
        for (T[] ts : arrays) {
            list.addAll(asArrayList(ts));
        }
        return list;
    }

    /**
     * 寻找上下文中的 Activity 对象
     */
    @Nullable
    static Activity findActivity(@NonNull Context context) {
        do {
            if (context instanceof Activity) {
                return (Activity) context;
            } else if (context instanceof ContextWrapper) {
                // android.content.ContextWrapper
                // android.content.MutableContextWrapper
                // android.support.v7.view.ContextThemeWrapper
                context = ((ContextWrapper) context).getBaseContext();
            } else {
                return null;
            }
        } while (context != null);
        return null;
    }

    /**
     * 获取当前应用 Apk 在 AssetManager 中的 Cookie，如果获取失败，则为 0
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    @SuppressLint("PrivateApi")
    static int findApkPathCookie(@NonNull Context context, @NonNull String apkPath) {
        AssetManager assets = context.getAssets();
        Integer cookie;

        try {

            if (AndroidVersion.getTargetSdkVersionCode(context) >= AndroidVersion.ANDROID_9 &&
                    AndroidVersion.getAndroidVersionCode() >= AndroidVersion.ANDROID_9 &&
                    AndroidVersion.getAndroidVersionCode() < AndroidVersion.ANDROID_11) {

                // 反射套娃操作：实测这种方式只在 Android 9.0 和 Android 10.0 有效果，在 Android 11 上面就失效了
                Method metaGetDeclaredMethod = Class.class.getDeclaredMethod(
                        "getDeclaredMethod", String.class, Class[].class);
                metaGetDeclaredMethod.setAccessible(true);
                // 注意 AssetManager.findCookieForPath 是 Android 9.0（API 28）的时候才添加的方法
                // 而 Android 9.0 用的是 AssetManager.addAssetPath 来获取 cookie
                // 具体可以参考 PackageParser.parseBaseApk 方法源码的实现
                Method findCookieForPathMethod = (Method) metaGetDeclaredMethod.invoke(AssetManager.class,
                        "findCookieForPath", new Class[]{String.class});
                if (findCookieForPathMethod != null) {
                    findCookieForPathMethod.setAccessible(true);
                    cookie = (Integer) findCookieForPathMethod.invoke(context.getAssets(), apkPath);
                    if (cookie != null) {
                        return cookie;
                    }
                }
            }

            Method addAssetPathMethod = assets.getClass().getDeclaredMethod("addAssetPath", String.class);
            cookie = (Integer) addAssetPathMethod.invoke(assets, apkPath);
            if (cookie != null) {
                return cookie;
            }

        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        // 获取失败直接返回 0
        // 为什么不直接返回 Integer，而是返回 int 类型？
        // 去看看 AssetManager.findCookieForPath 获取失败会返回什么就知道了
        return 0;
    }

    /**
     * 判断是否适配了分区存储
     */
    static boolean isScopedStorage(@NonNull Context context) {
        try {
            String metaKey = "ScopedStorage";
            Bundle metaData = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            if (metaData != null && metaData.containsKey(metaKey)) {
                return Boolean.parseBoolean(String.valueOf(metaData.get(metaKey)));
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 锁定当前 Activity 的方向
     */
    @SuppressLint("SwitchIntDef")
    static void lockActivityOrientation(@NonNull Activity activity) {
        try {
            // 兼容问题：在 Android 8.0 的手机上可以固定 Activity 的方向，但是这个 Activity 不能是透明的，否则就会抛出异常
            // 复现场景：只需要给 Activity 主题设置 <item name="android:windowIsTranslucent">true</item> 属性即可
            switch (activity.getResources().getConfiguration().orientation) {
                case Configuration.ORIENTATION_LANDSCAPE:
                    activity.setRequestedOrientation(PermissionUtils.isActivityReverse(activity) ?
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE :
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    break;
                case Configuration.ORIENTATION_PORTRAIT:
                    activity.setRequestedOrientation(PermissionUtils.isActivityReverse(activity) ?
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT :
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    break;
                default:
                    break;
            }
        } catch (IllegalStateException e) {
            // java.lang.IllegalStateException: Only fullscreen activities can request orientation
            e.printStackTrace();
        }
    }

    /**
     * 判断 Activity 是否反方向旋转了
     */
    static boolean isActivityReverse(@NonNull Activity activity) {
        // 获取 Activity 旋转的角度
        int activityRotation;
        if (AndroidVersion.isAndroid11()) {
            activityRotation = activity.getDisplay().getRotation();
        } else {
            activityRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        }
        switch (activityRotation) {
            case Surface.ROTATION_180:
            case Surface.ROTATION_270:
                return true;
            case Surface.ROTATION_0:
            case Surface.ROTATION_90:
            default:
                return false;
        }
    }

    /**
     * 判断这个意图的 Activity 是否存在
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean areActivityIntent(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        // 这里为什么不用 Intent.resolveActivity(intent) != null 来判断呢？
        // 这是因为在 OPPO R7 Plus （Android 5.0）会出现误判，明明没有这个 Activity，却返回了 ComponentName 对象
        PackageManager packageManager = context.getPackageManager();
        if (AndroidVersion.isAndroid13()) {
            return !packageManager.queryIntentActivities(intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)).isEmpty();
        }
        return !packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
    }

    /**
     * 根据传入的权限自动选择最合适的权限设置页
     *
     * @param permissions                 请求失败的权限
     */
    static Intent getSmartPermissionIntent(@NonNull Context context, @Nullable List<String> permissions) {
        // 如果失败的权限里面不包含特殊权限
        if (permissions == null || permissions.isEmpty()) {
            return PermissionIntentManager.getApplicationDetailsIntent(context);
        }

        // 危险权限统一处理
        if (!PermissionApi.containsSpecialPermission(permissions)) {
            if (permissions.size() == 1) {
                return PermissionApi.getPermissionIntent(context, permissions.get(0));
            }
            return PermissionIntentManager.getApplicationDetailsIntent(context);
        }

        // 特殊权限统一处理
        switch (permissions.size()) {
            case 1:
                // 如果当前只有一个权限被拒绝了
                return PermissionApi.getPermissionIntent(context, permissions.get(0));
            case 2:
                if (!AndroidVersion.isAndroid13() &&
                        PermissionUtils.containsPermission(permissions, Permission.NOTIFICATION_SERVICE) &&
                        PermissionUtils.containsPermission(permissions, Permission.POST_NOTIFICATIONS)) {
                    return PermissionApi.getPermissionIntent(context, Permission.NOTIFICATION_SERVICE);
                }
                break;
            case 3:
                if (AndroidVersion.isAndroid11() &&
                        PermissionUtils.containsPermission(permissions, Permission.MANAGE_EXTERNAL_STORAGE) &&
                        PermissionUtils.containsPermission(permissions, Permission.READ_EXTERNAL_STORAGE) &&
                        PermissionUtils.containsPermission(permissions, Permission.WRITE_EXTERNAL_STORAGE)) {
                    return PermissionApi.getPermissionIntent(context, Permission.MANAGE_EXTERNAL_STORAGE);
                }
                break;
            default:
                break;
        }
        return PermissionIntentManager.getApplicationDetailsIntent(context);
    }

    /**
     * 判断两个权限字符串是否为同一个
     */
    static boolean equalsPermission(@NonNull String permission1, @NonNull String permission2) {
        int length = permission1.length();
        if (length != permission2.length()) {
            return false;
        }

        // 因为权限字符串都是 android.permission 开头
        // 所以从最后一个字符开始判断，可以提升 equals 的判断效率
        for (int i = length - 1; i >= 0; i--) {
            if (permission1.charAt(i) != permission2.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断权限集合中是否包含某个权限
     */
    static boolean containsPermission(@NonNull Collection<String> permissions, @NonNull String permission) {
        if (permissions.isEmpty()) {
            return false;
        }
        for (String s : permissions) {
            // 使用 equalsPermission 来判断可以提升代码效率
            if (equalsPermission(s, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取包名 uri
     */
    static Uri getPackageNameUri(@NonNull Context context) {
        return Uri.parse("package:" + context.getPackageName());
    }
}