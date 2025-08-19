package top.littlew.acer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.Menu;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {
    @SuppressLint("StaticFieldLeak")
    private static Context mContext;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        if (lpparam.packageName.equals("com.android.settings")) {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.settings.SettingsActivity", lpparam.classLoader), "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mContext = (Context) param.thisObject;
                }
            });

            Class<?> clonedAppsPreferenceControllerClass = XposedHelpers.findClass("com.android.settings.applications.ClonedAppsPreferenceController", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(clonedAppsPreferenceControllerClass, "getAvailabilityStatus", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(0);
                }
            });

            XposedHelpers.findAndHookMethod(clonedAppsPreferenceControllerClass, "updateSummary", int.class, int.class, new XC_MethodHook() {
                @SuppressLint({"QueryPermissionsNeeded", "DiscouragedApi"})
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mContext == null) {
                        return;
                    }

                    PackageManager pm = mContext.getPackageManager();

                    List<String> cloneableApps = getCloneableApps();

                    List<PackageInfo> primaryInstalledPackagesAsUser = (List<PackageInfo>) XposedHelpers.callMethod(pm, "getInstalledPackagesAsUser", 0, XposedHelpers.callStaticMethod(UserHandle.class, "myUserId"));

                    List<String> primaryUserApps = primaryInstalledPackagesAsUser.stream().map(x -> x.packageName).toList();

                    int availableAppsCount = (int) cloneableApps.stream().filter(primaryUserApps::contains).count();

                    int clonedUserId = (int) XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.settings.Utils", lpparam.classLoader), "getCloneUserId", mContext);
                    int clonedAppsCount = 0;

                    if (!(clonedUserId == -1)) {
                        List<PackageInfo> cloneProfileInstalledPackagesAsUser = (List<PackageInfo>) XposedHelpers.callMethod(pm, "getInstalledPackagesAsUser", 0, clonedUserId);

                        List<String> cloneProfileApps = cloneProfileInstalledPackagesAsUser.stream().map(x -> x.packageName).toList();

                        clonedAppsCount = (int) cloneableApps.stream().filter(cloneProfileApps::contains).count();
                    }

                    Object mPreference = XposedHelpers.getObjectField(param.thisObject, "mPreference");
                    if (mPreference != null) {
                        XposedHelpers.callMethod(mPreference, "setSummary", String.format(mContext.getResources().getString(mContext.getResources().getIdentifier("cloned_apps_summary", "string", mContext.getPackageName())), clonedAppsCount, availableAppsCount - clonedAppsCount));
                    }

                    param.setResult(null);
                }
            });

            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.settings.applications.AppStateClonedAppsBridge", lpparam.classLoader), "updateExtraInfo", XposedHelpers.findClass("com.android.settingslib.applications.ApplicationsState$AppEntry", lpparam.classLoader), String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.setObjectField(param.thisObject, "mAllowedApps", getCloneableApps());
                }
            });

            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.settings.applications.manageapplications.ManageApplications", lpparam.classLoader), "updateOptionsMenu", new XC_MethodHook() {
                @SuppressLint("DiscouragedApi")
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getObjectField(param.thisObject, "mListType").equals(17) && (int) XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.settings.Utils", lpparam.classLoader), "getCloneUserId", mContext) > 0) {
                        Menu mOptionsMenu = (Menu) XposedHelpers.getObjectField(param.thisObject, "mOptionsMenu");
                        if (mOptionsMenu != null) {
                            mOptionsMenu.findItem(mContext.getResources().getIdentifier("delete_all_app_clones", "id", mContext.getPackageName())).setVisible(true);
                        }
                    }
                }
            });
        } else {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView$AdapterHolder", lpparam.classLoader), "setup", View.class, Predicate.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if ((int) XposedHelpers.getObjectField(param.thisObject, "mType") == 0) {
                        Object allAppsContainerView = XposedHelpers.getObjectField(param.thisObject, "this$0");

                        Predicate workProfileMatcher = (Predicate) XposedHelpers.callMethod(XposedHelpers.getObjectField(allAppsContainerView, "mWorkManager"), "getItemInfoMatcher");
                        Predicate privateProfileMatcher = (Predicate) XposedHelpers.callMethod(XposedHelpers.getObjectField(allAppsContainerView, "mPrivateProfileManager"), "getItemInfoMatcher");

                        Predicate WorkOrPrivate = workProfileMatcher.or(privateProfileMatcher);

                        Predicate filter = WorkOrPrivate.negate();

                        param.args[1] = filter;
                    }
                }
            });
        }
    }

    private List<String> getCloneableApps() {
        if (mContext == null) {
            return new ArrayList<>();
        }

        @SuppressLint("DiscouragedApi") List<String> cloneableApps = new ArrayList<>(Arrays.asList(mContext.getResources().getStringArray(mContext.getResources().getIdentifier("cloneable_apps", "array", "android"))));

        SharedPreferences sharedPrefs = mContext.getSharedPreferences("cloneable_apps", Context.MODE_PRIVATE);
        List<String> newCloneableApps = new ArrayList<>(sharedPrefs.getStringSet("cloneable_apps", new HashSet<>()));

        cloneableApps.addAll(newCloneableApps);

        if (cloneableApps.isEmpty()) {
            List<PackageInfo> primaryInstalledPackagesAsUser = (List<PackageInfo>) XposedHelpers.callMethod(mContext.getPackageManager(), "getInstalledPackagesAsUser", 0, XposedHelpers.callStaticMethod(UserHandle.class, "myUserId"));

            for (PackageInfo pkgInfo : primaryInstalledPackagesAsUser) {
                if ((pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    cloneableApps.add(pkgInfo.packageName);
                }
            }
        }

        return cloneableApps;
    }
}