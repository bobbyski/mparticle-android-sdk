package com.mparticle.messaging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * <code>BroadcastReceiver</code> to be used to listen for, manipulate, and react to GCM notifications.
 *
 * In order to use this class:
 *
 * 1. Create your own class which extends this one, for example MyPushReceiver.java
 * 2. Add the following to your <code>AndroidManifest.xml</code>, replacing <code>YOURPACKAGENAME</code> and the name of your <code>BroadcastReceiver</code>:
 * <pre>
 *  {@code <receiver android:name="YOURPACKAGENAME.MyPushReceiver">
 *      <intent-filter>
 *          <action android:name="com.mparticle.push.RECEIVE" />
 *          <action android:name="com.mparticle.push.TAP" />
 *          <category android:name="YOURPACKAGENAME"/>
 *      </intent-filter>
 *  </receiver> }</pre>
 *
 * @see #onNotificationReceived(AbstractCloudMessage)
 * @see #onNotificationTapped(AbstractCloudMessage, CloudAction)
 *
 */
public class PushAnalyticsReceiver extends BroadcastReceiver implements PushAnalyticsReceiverCallback {

    @Override
    public final void onReceive(Context context, Intent intent) {
        MPMessagingRouter.onReceive(context, intent, this);
    }

    @Override
    public boolean onNotificationReceived(AbstractCloudMessage message) {
        return false;
    }

    @Override
    public boolean onNotificationTapped(AbstractCloudMessage message, CloudAction action) {
        return false;
    }
}