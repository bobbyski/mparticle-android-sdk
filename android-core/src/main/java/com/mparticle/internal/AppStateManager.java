package com.mparticle.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import com.mparticle.BuildConfig;
import com.mparticle.MPEvent;
import com.mparticle.MParticle;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 *
 * This class is responsible for managing sessions, by detecting how many
 *
 */
 public class AppStateManager {

    private ConfigManager mConfigManager;
    Context mContext;
    private final SharedPreferences mPreferences;
    private Session mCurrentSession = new Session();
    private WeakReference<Activity> mCurrentActivityReference = null;

    private String mCurrentActivityName;
    /**
     * This boolean is important in determining if the app is running due to the user opening the app,
     * or if we're running due to the reception of a Intent such as a GCM message.
     */
    public static boolean mInitialized;

    AtomicLong mLastStoppedTime;
    /**
     * it can take some time between when an activity stops and when a new one (or the same one on a configuration change/rotation)
     * starts again, so use this handler and ACTIVITY_DELAY to determine when we're *really" in the background
     */
    Handler delayedBackgroundCheckHandler = new Handler();
    private static final long ACTIVITY_DELAY = 1000;


    /**
     * Some providers need to know for the given session, how many 'interruptions' there were - how many
     * times did the user leave and return prior to the session timing out.
     */
    AtomicInteger mInterruptionCount = new AtomicInteger(0);

    /**
     * Constants used by the messaging/push framework to describe the app state when various
     * interactions occur (receive/show/tap).
     */
    public static final String APP_STATE_FOREGROUND = "foreground";
    public static final String APP_STATE_BACKGROUND = "background";
    public static final String APP_STATE_NOTRUNNING = "not_running";

    /**
     * Important to determine foreground-time length for a given session.
     * Uses the system-uptime clock to avoid devices which wonky clocks, or clocks
     * that change while the app is running.
     */
    private long mLastForegroundTime;

    boolean mUnitTesting = false;
    private MessageManager mMessageManager;

    public AppStateManager(Context context, boolean unitTesting){
        mUnitTesting = unitTesting;
        mContext = context.getApplicationContext();
        mLastStoppedTime = new AtomicLong(getTime());
        mPreferences = context.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
    }

    public AppStateManager(Context context) {
        this(context, false);
    }

    public void init(int apiVersion) {
        if (apiVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            setupLifecycleCallbacks();
        }
    }

    public void setConfigManager(ConfigManager manager){
        mConfigManager = manager;
    }

    public void setMessageManager(MessageManager manager){
        mMessageManager = manager;
    }

    private long getTime(){
        if (mUnitTesting){
            return System.currentTimeMillis();
        }else {
            return SystemClock.elapsedRealtime();
        }
    }

    public void onActivityResumed(Activity activity) {
        try {
            mCurrentActivityName = AppStateManager.getActivityName(activity);

            int interruptions = mInterruptionCount.get();
            if (!mInitialized || !getSession().isActive()) {
                mInterruptionCount = new AtomicInteger(0);
            }
            String previousSessionPackage = null;
            String previousSessionUri = null;
            String previousSessionParameters = null;
            if (activity != null){
                ComponentName callingApplication = activity.getCallingActivity();
                if (callingApplication != null) {
                    previousSessionPackage = callingApplication.getPackageName();
                }
                if(activity.getIntent() != null) {
                    previousSessionUri = activity.getIntent().getDataString();

                    if (activity.getIntent().getExtras() != null && activity.getIntent().getExtras().getBundle(Constants.External.APPLINK_KEY) != null) {
                        JSONObject parameters = new JSONObject();
                        try {
                            parameters.put(Constants.External.APPLINK_KEY, MPUtility.wrapExtras(activity.getIntent().getExtras().getBundle(Constants.External.APPLINK_KEY)));
                        } catch (Exception e) {

                        }
                        previousSessionParameters = parameters.toString();
                    }
                }
            }

            mCurrentSession.updateBackgroundTime(mLastStoppedTime, getTime());

            if (!mInitialized) {
                mInitialized = true;
                 logStateTransition(Constants.StateTransitionType.STATE_TRANS_INIT,
                         mCurrentActivityName,
                        0,
                        0,
                        previousSessionUri,
                        previousSessionParameters,
                        previousSessionPackage,
                        0);
                mLastForegroundTime = getTime();
            } else if (isBackgrounded() && mLastStoppedTime.get() > 0) {
                logStateTransition(Constants.StateTransitionType.STATE_TRANS_FORE,
                        mCurrentActivityName,
                        mLastStoppedTime.get() - mLastForegroundTime,
                        getTime() - mLastStoppedTime.get(),
                        previousSessionUri,
                        previousSessionParameters,
                        previousSessionPackage,
                        interruptions);
                ConfigManager.log(MParticle.LogLevel.DEBUG, "App foregrounded.");
                mLastForegroundTime = getTime();
            }

            if (mCurrentActivityReference != null) {
                mCurrentActivityReference.clear();
                mCurrentActivityReference = null;
            }
            mCurrentActivityReference = new WeakReference<Activity>(activity);

            if (MParticle.getInstance().isAutoTrackingEnabled()) {
                MParticle.getInstance().logScreen(mCurrentActivityName);
            }

            MParticle.getInstance().getKitManager().onActivityResumed(activity);
        }catch (Exception e){
            if (BuildConfig.MP_DEBUG) {
                ConfigManager.log(MParticle.LogLevel.ERROR, "Failed while trying to track activity resume: " + e.getMessage());
            }
        }
    }

    public void onActivityPaused(Activity activity) {
        try {
            mPreferences.edit().putBoolean(Constants.PrefKeys.CRASHED_IN_FOREGROUND, false).apply();
            mLastStoppedTime = new AtomicLong(getTime());
            if (mCurrentActivityReference != null && activity == mCurrentActivityReference.get()) {
                mCurrentActivityReference.clear();
                mCurrentActivityReference = null;
            }

            delayedBackgroundCheckHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (isBackgrounded()) {
                            checkSessionTimeout();
                            logBackgrounded();
                        }
                    }catch (Exception e){

                    }
                }
            }, ACTIVITY_DELAY);

            if (MParticle.getInstance().isAutoTrackingEnabled()) {
                MParticle.getInstance().logScreen(
                        new MPEvent.Builder(AppStateManager.getActivityName(activity))
                                .internalNavigationDirection(false)
                                .build()
                );
            }
            MParticle.getInstance().getKitManager().onActivityPaused(activity);
        }catch (Exception e){
            if (BuildConfig.MP_DEBUG) {
                ConfigManager.log(MParticle.LogLevel.ERROR, "Failed while trying to track activity pause: " + e.getMessage());
            }
        }
    }

    public void ensureActiveSession() {
        Session session = getSession();
        session.mLastEventTime = System.currentTimeMillis();
        if (!session.isActive()) {
            newSession();
        }else{
            mMessageManager.updateSessionEnd(getSession());
        }
    }

    void logStateTransition(String transitionType, String currentActivity, long previousForegroundTime, long suspendedTime, String dataString, String launchParameters, String launchPackage, int interruptions) {
        if (mConfigManager.isEnabled()) {
            ensureActiveSession();
            mMessageManager.logStateTransition(transitionType,
                    currentActivity,
                    dataString,
                    launchParameters,
                    launchPackage,
                    previousForegroundTime,
                    suspendedTime,
                    interruptions
            );
        }
    }

    public void logStateTransition(String transitionType, String currentActivity) {
        logStateTransition(transitionType, currentActivity, 0, 0, null, null, null, 0);
    }

    /**
     * Creates a new session and generates the start-session message.
     */
    private void newSession() {
        startSession();
        mMessageManager.startSession();
        ConfigManager.log(MParticle.LogLevel.DEBUG, "Started new session");
        mMessageManager.startUploadLoop();
        enableLocationTracking();
        checkSessionTimeout();
    }

    private void enableLocationTracking(){
        if (mPreferences.contains(Constants.PrefKeys.LOCATION_PROVIDER)){
            String provider = mPreferences.getString(Constants.PrefKeys.LOCATION_PROVIDER, null);
            long minTime = mPreferences.getLong(Constants.PrefKeys.LOCATION_MINTIME, 0);
            long minDistance = mPreferences.getLong(Constants.PrefKeys.LOCATION_MINDISTANCE, 0);
            if (provider != null && minTime > 0 && minDistance > 0){
                MParticle.getInstance().enableLocationTracking(provider, minTime, minDistance);
            }
        }
    }

    private void checkSessionTimeout() {
        delayedBackgroundCheckHandler.postDelayed( new Runnable() {
            @Override
            public void run() {
                Session session = getSession();
                if (0 != session.mSessionStartTime &&
                        isBackgrounded()
                        && session.isTimedOut(mConfigManager.getSessionTimeout())
                        && !MParticle.getInstance().Media().getAudioPlaying()) {
                    ConfigManager.log(MParticle.LogLevel.DEBUG, "Session timed out");
                    endSession();
                }
            }
        }, mConfigManager.getSessionTimeout());
    }

    public void onActivityCreated(Activity activity, Bundle savedInstanceState){
        MParticle.getInstance().getKitManager().onActivityCreated(activity, savedInstanceState);
    }

    public void onActivityStarted(Activity activity){
        MParticle.getInstance().getKitManager().onActivityStarted(activity);
    }

    public void onActivityStopped(Activity activity) {
        MParticle.getInstance().getKitManager().onActivityStopped(activity);
    }

    private void logBackgrounded(){
        logStateTransition(Constants.StateTransitionType.STATE_TRANS_BG, mCurrentActivityName);
        mCurrentActivityName = null;
        ConfigManager.log(MParticle.LogLevel.DEBUG, "App backgrounded.");
        mInterruptionCount.incrementAndGet();
    }

    @TargetApi(14)
    private void setupLifecycleCallbacks() {
        ((Application) mContext).registerActivityLifecycleCallbacks(new MPLifecycleCallbackDelegate(this));
    }

    public boolean isBackgrounded() {
        return !mInitialized || (mCurrentActivityReference == null && (getTime() - mLastStoppedTime.get() >= ACTIVITY_DELAY));
    }

    private static String getActivityName(Activity activity) {
        return activity.getClass().getCanonicalName();
    }

    public String getCurrentActivityName() {
        return mCurrentActivityName;
    }

    public Session getSession() {
        return mCurrentSession;
    }

    public void endSession() {
        ConfigManager.log(MParticle.LogLevel.DEBUG, "Ended session");
        mMessageManager.endSession(mCurrentSession);
        disableLocationTracking();
        mCurrentSession = new Session();
    }

    private void disableLocationTracking() {
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.remove(Constants.PrefKeys.LOCATION_PROVIDER)
                .remove(Constants.PrefKeys.LOCATION_MINTIME)
                .remove(Constants.PrefKeys.LOCATION_MINDISTANCE)
                .apply();
        MParticle.getInstance().disableLocationTracking();
    }

    public void startSession() {
        mCurrentSession = new Session().start();
        enableLocationTracking();
    }

    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        MParticle.getInstance().getKitManager().onActivitySaveInstanceState(activity, outState);
    }

    public void onActivityDestroyed(Activity activity) {
        MParticle.getInstance().getKitManager().onActivityDestroyed(activity);
    }

    public WeakReference<Activity> getCurrentActivity() {
        return mCurrentActivityReference;
    }
}
