/*
 * Copyright (C) 2026 DiamaneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.cleanimages;

import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;

import de.diamaneos.decode.client.VmDecode;
import de.diamaneos.sanitizer.decode.AppImageFiles;
import de.diamaneos.sanitizer.decode.AppImagesRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DiamaneOS: makes the clean copies of apps' launcher icons and splash images ahead of time, so no system process
 * shows an app's icon or splash without them. Which files an app names is resolved in the decode service's process
 * ({@code VmDecode.listAppImageFiles}), never here, and each file is then made by {@link CleanImageMaker}.
 *
 * <ul>
 * <li>When an app is installed or updated, or a launcher activity of it or the app itself is enabled or disabled:
 * its launcher activities, both night modes. An app listed in the last minutes with the same APK is not listed again.
 * An app whose list failed (its resource table, the VM down) is tried again later, a few times.
 * <li>After the first boot, an OS update, or a change of display size or language: one pass over every launcher app
 * of every user, at background priority, the apps the user sees first first (recent tasks, then the most used, then
 * the rest of the app drawer), checked again until every file has its copy (or the VM refused it), and given up after
 * a few rounds (the gates still ask for what they miss). Done passes are written down, so a normal boot does nothing.
 * <li>When an app is removed for a user, its copies go.
 * </ul>
 */
final class CleanImageWarmup {
    private static final String TAG = "CleanImages";
    private static final String PASS_FILE = "diamaneos_clean_images.pass";
    // The version of what a pass makes: a change makes every phone run the pass again.
    private static final int PASS_VERSION = 1;
    private static final long PASS_DELAY_MS = 60_000;
    private static final long RECHECK_MS = 2 * 60_000;
    private static final long RETRY_MS = 15 * 60_000;
    private static final int RECHECKS = 3;
    // Rounds of one pass at most, so a file that can never be made (the VM down for good, a full store) cannot keep
    // the pass running: after them the pass is written down and the gates' own requests fill what is left.
    private static final int MAX_ROUNDS = 8;
    private static final long LIST_TIMEOUT_MS = 10_000;
    private static final long STEP_MS = 200;
    // An app listed this recently, with the same APK, is not listed again for a change of its components.
    private static final long RELIST_MS = 10 * 60_000;
    // An app whose list failed is tried again after this (twice as late each time), at most APP_RETRIES times.
    private static final long APP_RETRY_MS = 5 * 60_000;
    private static final int APP_RETRIES = 4;

    private static CleanImageWarmup sInstance;

    private final Context mContext;
    private final Handler mHandler;
    private final Set<String> mPending = new LinkedHashSet<>(); // "package/user", on mHandler only
    private boolean mPassScheduled; // on mHandler only
    private String mPassState; // the state the running pass makes copies for, on mHandler only
    private final List<String> mPassKeys = new ArrayList<>(); // on mHandler only
    private final Set<String> mPassApps = new LinkedHashSet<>(); // apps of the pass not listed yet, on mHandler only
    private boolean mPassListed = true; // every app of the pass was listed, on mHandler only
    private int mChecks; // on mHandler only
    private int mPassGeneration; // on mHandler only
    // "package/user": when it was last listed and from which APK, and how often its list failed, on mHandler only.
    private final Map<String, Long> mListedAt = new HashMap<>();
    private final Map<String, String> mListedApk = new HashMap<>();
    private final Map<String, Integer> mFailures = new HashMap<>();

    private CleanImageWarmup(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread("CleanImagesWarmup", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    static synchronized void start(Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new CleanImageWarmup(context);
        sInstance.register();
    }

    private void register() {
        final IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri data = intent.getData();
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (data == null || uid < 0) {
                    return;
                }
                final CleanImageMaker maker = CleanImageMaker.get(mContext);
                maker.packagesChanged();
                final String packageName = data.getSchemeSpecificPart();
                final int userId = UserHandle.getUserId(uid);
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        maker.forgetPackage(packageName, userId);
                    }
                    return;
                }
                if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                        && !launcherChange(packageName, userId,
                                intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST))) {
                    return; // Another component (an icon alias toggled back and forth is one): nothing to list.
                }
                queue(packageName, userId);
            }
        }, UserHandle.ALL, packages, null, mHandler);
        // Installs before this point (early in the boot) are seen: the package map is made again.
        CleanImageMaker.get(mContext).packagesChanged();
        final IntentFilter config = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        config.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                schedulePass(PASS_DELAY_MS);
            }
        }, config, null, mHandler);
        schedulePass(PASS_DELAY_MS);
    }

    // On mHandler: whether a change of the app's components touches what the warm-up lists (the app itself, or one of
    // its launcher activities), and the app was not listed lately from the same APK.
    private boolean launcherChange(String packageName, int userId, String[] changed) {
        if (changed == null || changed.length == 0) {
            return false;
        }
        boolean relevant = Arrays.asList(changed).contains(packageName);
        String apk = null;
        try {
            final List<ResolveInfo> activities = mContext.getPackageManager().queryIntentActivitiesAsUser(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DISABLED_COMPONENTS, userId);
            for (ResolveInfo info : activities) {
                if (info.activityInfo == null) {
                    continue;
                }
                apk = info.activityInfo.applicationInfo.sourceDir;
                final String name = info.activityInfo.name;
                for (String c : changed) {
                    relevant |= name.equals(c) || name.equals(packageName + c)
                            || name.equals(packageName + "." + c);
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (!relevant) {
            return false;
        }
        final String app = packageName + '/' + userId;
        final Long listed = mListedAt.get(app);
        return listed == null || SystemClock.elapsedRealtime() - listed > RELIST_MS
                || apk == null || !apk.equals(mListedApk.get(app));
    }

    // On mHandler: the app is listed again after a back-off, unless it failed too often.
    private void retryLater(String packageName, int userId) {
        final String app = packageName + '/' + userId;
        final int failures = mFailures.getOrDefault(app, 0) + 1;
        mFailures.put(app, failures);
        if (failures > APP_RETRIES) {
            Log.w(TAG, "warm-up of " + packageName + " given up after " + APP_RETRIES
                    + " tries, the gates ask for what they miss");
            return;
        }
        mHandler.postDelayed(() -> queue(packageName, userId), APP_RETRY_MS << (failures - 1));
    }

    // On mHandler: an app's launcher activities, made after what is queued already.
    private void queue(String packageName, int userId) {
        if (packageName == null) {
            return;
        }
        if (mPending.add(packageName + '/' + userId)) {
            mHandler.post(this::runNext);
        }
    }

    private void runNext() {
        if (mPending.isEmpty()) {
            return;
        }
        final String next = mPending.iterator().next();
        mPending.remove(next);
        final int slash = next.lastIndexOf('/');
        final List<String> keys = warm(next.substring(0, slash), Integer.parseInt(next.substring(slash + 1)));
        if (mPassState != null && mPassApps.remove(next)) {
            if (keys == null) {
                mPassListed = false;
            } else {
                mPassKeys.addAll(keys);
            }
            if (mPassApps.isEmpty()) {
                final int generation = mPassGeneration;
                mHandler.postDelayed(() -> checkPass(generation), RECHECK_MS);
            }
        }
        if (!mPending.isEmpty()) {
            mHandler.postDelayed(this::runNext, STEP_MS);
        }
    }

    // Lists the app's files in the decode service and asks the maker for each: the keys asked for, or null when the
    // list could not be had (the VM down).
    private List<String> warm(String packageName, int userId) {
        final List<ResolveInfo> activities;
        try {
            activities = mContext.getPackageManager().queryIntentActivitiesAsUser(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.GET_SHARED_LIBRARY_FILES, userId);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        if (activities == null || activities.isEmpty()) {
            return new ArrayList<>();
        }
        final ActivityInfo first = activities.get(0).activityInfo;
        if (first == null || CleanCopyKey.isSystemImage(first.applicationInfo.sourceDir)) {
            return new ArrayList<>(); // The system image's files are decoded as before: nothing to make.
        }
        final List<Integer> themes = new ArrayList<>();
        final List<Integer> icons = new ArrayList<>();
        for (ResolveInfo info : activities) {
            final ActivityInfo ai = info.activityInfo;
            if (ai == null || !packageName.equals(ai.packageName)) {
                continue;
            }
            themes.add(ai.getThemeResource() != 0 ? ai.getThemeResource()
                    : com.android.internal.R.style.Theme_DeviceDefault_DayNight);
            icons.add(ai.getIconResource());
        }
        final int display = mContext.getResources().getConfiguration().densityDpi;
        final AppImagesRequest request = new AppImagesRequest();
        request.packageName = packageName;
        request.userId = userId;
        request.themes = themes.stream().mapToInt(Integer::intValue).toArray();
        request.icons = icons.stream().mapToInt(Integer::intValue).toArray();
        request.densities = densities(display);
        try {
            request.splashThemeName = ActivityThread.getPackageManager().getSplashScreenTheme(packageName, userId);
        } catch (Exception e) {
            request.splashThemeName = null;
        }
        final String app = packageName + '/' + userId;
        mListedAt.put(app, SystemClock.elapsedRealtime());
        mListedApk.put(app, first.applicationInfo.sourceDir);
        final AppImageFiles files;
        try {
            files = VmDecode.listAppImageFiles(mContext, request, VmDecode.deadlineIn(LIST_TIMEOUT_MS));
        } catch (VmDecode.UnavailableException e) {
            Log.w(TAG, "warm-up of " + packageName + " waits for the VM: " + e.getMessage());
            if (mPassState == null || !mPassApps.contains(app)) {
                retryLater(packageName, userId); // A pass runs again by itself.
            }
            return null;
        } catch (VmDecode.RejectedException e) {
            // This app's list failed (its resource table, or its resolver took too long): this app only.
            Log.w(TAG, "warm-up of " + packageName + " refused: " + e.getMessage());
            retryLater(packageName, userId);
            return new ArrayList<>();
        }
        mFailures.remove(app);
        // The resolver parsed the app's own resource table: only files of the app's own APKs, under resource
        // names of drawables and mipmaps, are taken from its answer.
        final Set<String> apks = new HashSet<>(Arrays.asList(first.applicationInfo.getAllApkPaths()));
        final int[] box = CleanCopyClient.box(mContext);
        final List<String> keys = new ArrayList<>();
        final CleanImageMaker maker = CleanImageMaker.get(mContext);
        // One use time for all of the app's files: the store's cap and trim order go app by app.
        final long stamp = maker.nextStamp();
        int dropped = 0;
        for (int i = 0; i < files.files.length; i++) {
            final String apk = files.apkPaths[i];
            final String file = files.files[i];
            final String resource = files.resources == null || files.resources.length <= i ? null
                    : files.resources[i];
            if (CleanCopyKey.isSystemImage(apk)) {
                continue;
            }
            if (!apks.contains(apk) || !CleanCopyKey.entryName(file)
                    || (resource != null && !CleanCopyKey.resourceName(resource))) {
                dropped++;
                continue;
            }
            // The density as the load sees it: the decode service rewrote it for a load at another density, as
            // Resources does (see AppImages).
            final int density = bitmapDensity(files.densities[i]);
            final String key = CleanCopyKey.of(apk, file, density, box[0], box[1]);
            if (key != null) {
                keys.add(key);
                maker.request(apk, file, density, box[0], box[1], resource, userId, false, stamp);
            }
        }
        if (dropped > 0) {
            Log.w(TAG, "warm-up of " + packageName + ": " + dropped + " files of the list not taken");
        }
        return keys;
    }

    // The densities system processes load launcher icons at: 0 (the package manager, at the display's own density),
    // and the density buckets from the display's up, where the launcher and the splash load them (the splash rounds
    // its icon density up to a bucket, see SplashImages.appIcon).
    static int[] densities(int display) {
        final List<Integer> list = new ArrayList<>();
        list.add(0);
        for (int bucket : new int[] {DisplayMetrics.DENSITY_XHIGH, DisplayMetrics.DENSITY_XXHIGH,
                DisplayMetrics.DENSITY_XXXHIGH}) {
            if (bucket >= display && list.size() < 3) {
                list.add(bucket);
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    // A resource value's density as a gate keys the bitmap made from it.
    static int bitmapDensity(int valueDensity) {
        if (valueDensity == TypedValue.DENSITY_DEFAULT) {
            return DisplayMetrics.DENSITY_DEFAULT;
        }
        if (valueDensity == TypedValue.DENSITY_NONE) {
            return 0; // Bitmap.DENSITY_NONE
        }
        return valueDensity;
    }

    // What the copies on disk were made for: the OS build, the density, the languages and the box.
    private String passState() {
        final Configuration config = mContext.getResources().getConfiguration();
        final int[] box = CleanCopyClient.box(mContext);
        return PASS_VERSION + "|" + Build.FINGERPRINT + "|" + config.densityDpi + "|"
                + config.getLocales().toLanguageTags() + "|" + box[0] + "x" + box[1];
    }

    private File passFile() {
        return new File(Environment.getDataSystemDirectory(), PASS_FILE);
    }

    private void schedulePass(long delayMs) {
        mHandler.post(() -> {
            if (mPassScheduled) {
                return;
            }
            mPassScheduled = true;
            mHandler.postDelayed(this::startPass, delayMs);
        });
    }

    // On mHandler: when the copies are not known to be complete for the current state, every launcher app of every
    // user is queued.
    private void startPass() {
        mPassScheduled = false;
        final String state = passState();
        if (CleanCopyClient.box(mContext)[0] < 16) {
            schedulePass(PASS_DELAY_MS); // No display yet.
            return;
        }
        try {
            if (state.equals(new String(Files.readAllBytes(passFile().toPath()), StandardCharsets.UTF_8))) {
                return;
            }
        } catch (IOException | RuntimeException e) {
            // No pass yet: the first boot.
        }
        if (state.equals(mPassState)) {
            return; // Running.
        }
        final Set<String> launcherApps = new LinkedHashSet<>();
        for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
            final List<ResolveInfo> activities;
            try {
                activities = mContext.getPackageManager().queryIntentActivitiesAsUser(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, user.id);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo info : activities) {
                if (info.activityInfo != null
                        && !CleanCopyKey.isSystemImage(info.activityInfo.applicationInfo.sourceDir)) {
                    launcherApps.add(info.activityInfo.packageName + '/' + user.id);
                }
            }
        }
        // The apps the user sees first go first: recent tasks, then the most used, then the rest.
        final Set<String> apps = new LinkedHashSet<>();
        for (String first : seenFirst()) {
            if (launcherApps.contains(first)) {
                apps.add(first);
            }
        }
        apps.addAll(launcherApps);
        mPassGeneration++;
        mPassState = state;
        mPassKeys.clear();
        mPassListed = true;
        mPassApps.clear();
        mPassApps.addAll(apps);
        Log.i(TAG, "warm-up: a pass over " + apps.size() + " launcher apps starts");
        if (apps.isEmpty()) {
            final int generation = mPassGeneration;
            mHandler.post(() -> checkPass(generation));
            return;
        }
        final boolean idle = mPending.isEmpty();
        mPending.addAll(apps);
        if (idle) {
            mHandler.post(this::runNext);
        }
    }

    // "package/user" of recent tasks, then of the apps used in the last weeks, newest first.
    private List<String> seenFirst() {
        final List<String> order = new ArrayList<>();
        try {
            final android.app.ActivityManager am = mContext.getSystemService(android.app.ActivityManager.class);
            for (android.app.ActivityManager.RecentTaskInfo task : am.getRecentTasks(32,
                    android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                if (task.baseIntent != null && task.baseIntent.getComponent() != null) {
                    order.add(task.baseIntent.getComponent().getPackageName() + '/' + task.userId);
                }
            }
        } catch (RuntimeException e) {
            // Without recent tasks.
        }
        try {
            final android.app.usage.UsageStatsManagerInternal usage =
                    com.android.server.LocalServices.getService(
                            android.app.usage.UsageStatsManagerInternal.class);
            final long now = System.currentTimeMillis();
            for (UserInfo user : mContext.getSystemService(UserManager.class).getAliveUsers()) {
                final List<android.app.usage.UsageStats> stats = usage == null ? null
                        : usage.queryUsageStatsForUser(user.id, android.app.usage.UsageStatsManager.INTERVAL_BEST,
                                now - 28L * 24 * 60 * 60 * 1000, now, false);
                if (stats == null) {
                    continue;
                }
                final List<android.app.usage.UsageStats> sorted = new ArrayList<>(stats);
                sorted.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
                for (android.app.usage.UsageStats s : sorted) {
                    order.add(s.getPackageName() + '/' + user.id);
                }
            }
        } catch (RuntimeException e) {
            // Without usage.
        }
        return order;
    }

    // On mHandler: the pass is written down once every file it asked for has its copy or was refused, or after
    // MAX_ROUNDS rounds.
    private void checkPass(int generation) {
        final String state = mPassState;
        if (state == null || generation != mPassGeneration || !mPassApps.isEmpty()) {
            return; // A newer pass took over, or this one has not listed every app yet.
        }
        final int open = mPassListed ? CleanImageMaker.get(mContext).open(mPassKeys) : -1;
        mPassState = null;
        if ((open != 0 && mChecks + 1 < MAX_ROUNDS) || !state.equals(passState())) {
            mChecks++;
            Log.i(TAG, "warm-up: " + (open < 0 ? "apps not listed (the VM down)" : open + " files to make")
                    + ", the pass runs again later");
            schedulePass(mChecks <= RECHECKS ? RECHECK_MS : RETRY_MS);
            return;
        }
        if (open != 0) {
            Log.w(TAG, "warm-up: the pass ends with " + (open < 0 ? "apps not listed" : open + " files not made")
                    + " after " + MAX_ROUNDS + " rounds, the gates ask for what they miss");
        }
        mChecks = 0;
        try {
            final File file = passFile();
            final File temp = new File(file.getPath() + ".tmp");
            Files.write(temp.toPath(), state.getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(file)) {
                temp.delete();
            }
            Log.i(TAG, "warm-up: the pass is done");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "warm-up: the pass is done but not written down: " + e);
        }
    }
}
