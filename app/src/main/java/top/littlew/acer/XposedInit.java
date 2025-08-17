package top.littlew.acer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {
    @SuppressLint("StaticFieldLeak")
    private static Context mContext;

    private static final int AVAILABLE = 0;
    private static final int CLONED_APPS_SUMMARY_STRING_ID = 0x7f140736;

    private static Class<?> UtilsClass;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.android.settings")) {
            return;
        }

        UtilsClass = XposedHelpers.findClass("com.android.settings.Utils", lpparam.classLoader);

        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.android.settings.SettingsActivity", lpparam.classLoader),
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mContext = (Context) param.thisObject;
                    }
                }
        );

        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("android.os.Flags", lpparam.classLoader), "allowPrivateProfile", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        });


        Class<?> clonedAppsPreferenceControllerClass = XposedHelpers.findClass("com.android.settings.applications.ClonedAppsPreferenceController", lpparam.classLoader);

        XposedHelpers.findAndHookMethod(
                clonedAppsPreferenceControllerClass,
                "getAvailabilityStatus",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(AVAILABLE);
                    }
                });

        XposedHelpers.findAndHookMethod(
                clonedAppsPreferenceControllerClass,
                "updatePreferenceSummary",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Allowing native logic to calculate Summary.
                    }
                });


        XposedHelpers.findAndHookMethod(
                clonedAppsPreferenceControllerClass,
                "updateSummary",
                int.class,
                int.class,
                new XC_MethodHook() {
                    @SuppressLint("QueryPermissionsNeeded")
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mContext == null) {
                            return;
                        }

                        PackageManager pm = mContext.getPackageManager();

                        List<String> primaryUserNonSystemAppPackages = new ArrayList<>();
                        for (PackageInfo pkgInfo : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                            if ((pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 &&
                                    !pkgInfo.packageName.equals(lpparam.packageName)) {
                                primaryUserNonSystemAppPackages.add(pkgInfo.packageName);
                            }
                        }

                        int currentlyClonedCount = 0;
                        int cloneUserId = getCloneUserIDInternal();
                        if (cloneUserId > 0) {
                            @SuppressLint("PackageManagerGetInstalledPackagesAsUser")
                            List<PackageInfo> cloneUserPackages = (List<PackageInfo>) XposedHelpers.callMethod(pm, "getInstalledPackagesAsUser", PackageManager.GET_ACTIVITIES, cloneUserId);

                            for (PackageInfo clonedPkgInfo : cloneUserPackages) {
                                if ((clonedPkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 &&
                                        primaryUserNonSystemAppPackages.contains(clonedPkgInfo.packageName)) {
                                    currentlyClonedCount++;
                                }
                            }
                        }

                        Object mPreference = XposedHelpers.getObjectField(param.thisObject, "mPreference");
                        if (mPreference != null) {
                            String newSummary = String.format(mContext.getResources().getString(CLONED_APPS_SUMMARY_STRING_ID), currentlyClonedCount, primaryUserNonSystemAppPackages.size());
                            XposedHelpers.callMethod(mPreference, "setSummary", newSummary);
                        }
                        param.setResult(null);
                    }
                });

        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.android.settings.applications.AppStateClonedAppsBridge", lpparam.classLoader),
                "updateExtraInfo",
                XposedHelpers.findClass("com.android.settingslib.applications.ApplicationsState$AppEntry", lpparam.classLoader),
                String.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object appEntry = param.args[0];
                        String pkg = (String) param.args[1];
                        ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.getObjectField(appEntry, "info");

                        int appEntryUserId = (int) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId", appInfo.uid);
                        int cloneUserId = getCloneUserIDInternal();

                        Boolean shouldDisplay;

                        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            shouldDisplay = Boolean.FALSE;
                        } else {
                            List<String> cloneProfileNonSystemAppPackages = getStrings(cloneUserId);

                            if (appEntryUserId == cloneUserId) {
                                shouldDisplay = Boolean.TRUE;
                            } else if (appEntryUserId == (int) XposedHelpers.callStaticMethod(UserHandle.class, "myUserId")) {
                                if (!cloneProfileNonSystemAppPackages.contains(pkg)) {
                                    shouldDisplay = Boolean.TRUE;
                                } else {
                                    shouldDisplay = Boolean.FALSE;
                                }
                            } else {
                                shouldDisplay = Boolean.FALSE;
                            }
                        }
                        XposedHelpers.setObjectField(appEntry, "extraInfo", shouldDisplay);
                    }
                });
    }

    private static List<String> getStrings(int cloneUserId) {
        List<String> cloneProfileNonSystemAppPackages = new ArrayList<>();
        if (cloneUserId > 0 && mContext != null) {
            @SuppressLint("PackageManagerGetInstalledPackagesAsUser")
            List<PackageInfo> installedClonedPackages = (List<PackageInfo>) XposedHelpers.callMethod(mContext.getPackageManager(), "getInstalledPackagesAsUser", PackageManager.GET_ACTIVITIES, cloneUserId);
            if (installedClonedPackages != null) {
                for (PackageInfo pInfo : installedClonedPackages) {
                    if ((pInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        cloneProfileNonSystemAppPackages.add(pInfo.packageName);
                    }
                }
            }
        }
        return cloneProfileNonSystemAppPackages;
    }

    private int getCloneUserIDInternal() {
        if (UtilsClass == null || mContext == null) {
            return -1;
        }
        return (int) XposedHelpers.callStaticMethod(UtilsClass, "getCloneUserId", mContext);
    }
}
