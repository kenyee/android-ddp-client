/*
* (c)Copyright 2013-2014 Ken Yee, KEY Enterprise Solutions 
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.keysolutions.ddpclient.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

/**
 * This class handles base/common functionality for DDP event handling using
 * Android's LocalBroadcastManager.  E.g., error handling, auto resume token login, etc.
 * Override the various methods to hook your own application handling in.
 * @author kenyee
 */
public class DDPBroadcastReceiver extends BroadcastReceiver {
    public final static boolean TRACE = false;		            // for debugging

    /** Activity to use for displaying error messages */
    private Activity mActivity;
    /** DDP singleton that holds all the state */
    private DDPStateSingleton mDDP;
    
    /**
     * Constructor for class
     * @param ddp DDP singleton
     * @param activity activity to display errors on
     */
    public DDPBroadcastReceiver(DDPStateSingleton ddp, Activity activity) {
        if (TRACE) Log.v("DDPBroadcastReceiver","DDPBroadcastReceiver - " + "ddp = [" + ddp + "], activity = [" + activity + "]");
        this.mActivity = activity;
        this.mDDP = ddp;
        // automatically register this receiver to handle local broadcast messages
        // we want error messages
        LocalBroadcastManager.getInstance(activity).registerReceiver(
                this, new IntentFilter(DDPStateSingleton.MESSAGE_ERROR));
        // we want connection state change messages so we know we're
        // disconnected
        LocalBroadcastManager.getInstance(activity).registerReceiver(
                this, new IntentFilter(DDPStateSingleton.MESSAGE_CONNECTION));
        // we want subscription update messages so we can update our Parties
        // class
        LocalBroadcastManager.getInstance(activity).registerReceiver(
                this, new IntentFilter(DDPStateSingleton.MESSAGE_SUBUPDATED));
        
        // if we're connected already, we should call the receiver's onConnect
        // so it can do any needed subscriptions because otherwise, it will never get called
        if (ddp.isConnected()) {
            this.onDDPConnect(ddp);
        }
    }

    /**
     * Handles receiving Android broadcast messages
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - " + "context = [" + context + "], intent = [" + intent + "],  action = " + intent.getAction() + "  state: " + intent.getExtras().getInt(DDPStateSingleton.MESSAGE_EXTRA_STATE));
        // display errors to the user
        Bundle bundle = intent.getExtras();
        if (intent.getAction().equals(DDPStateSingleton.MESSAGE_ERROR)) {
            if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive");
            String message = bundle.getString(DDPStateSingleton.MESSAGE_EXTRA_MSG);
            if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - ERROR - message:" + message);
            onError("Login Error", message);

        } else if (intent.getAction().equals( DDPStateSingleton.MESSAGE_CONNECTION)) {
            int state = bundle.getInt(DDPStateSingleton.MESSAGE_EXTRA_STATE);
            if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - CONNECTION - state: " + state);
            // state: 0 = NotLoggedIn // 1 = Connected // 2 = LoggedIn // 3 = Closed

            // testing to see what are the values
            //Log.v("DDPBroadcastReceiver", "onReceive - DDPStateSingleton.DDPSTATE.Closed.ordinal(): "       + DDPStateSingleton.DDPSTATE.Closed.ordinal());     // 3
            //Log.v("DDPBroadcastReceiver", "onReceive - DDPStateSingleton.DDPSTATE.Connected.ordinal(): "    + DDPStateSingleton.DDPSTATE.Connected.ordinal());  // 1
            //Log.v("DDPBroadcastReceiver", "onReceive - DDPStateSingleton.DDPSTATE.LoggedIn.ordinal(): "     + DDPStateSingleton.DDPSTATE.LoggedIn.ordinal());   // 2
            //Log.v("DDPBroadcastReceiver", "onReceive - DDPStateSingleton.DDPSTATE.NotLoggedIn.ordinal(): "  + DDPStateSingleton.DDPSTATE.NotLoggedIn.ordinal());// 0



            if (state == DDPStateSingleton.DDPSTATE.Closed.ordinal()) {
                if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - CONNECTION - was closed show error");
                // connection was closed, show error message
                onError("Disconnected","Websocket to server was closed");

            } else if (state == DDPStateSingleton.DDPSTATE.Connected.ordinal()) {
                if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - CONNECTION - CONNECTED > launch onDDPConnect where I create all subscriptions");
                onDDPConnect(mDDP);

            } else if (state == DDPStateSingleton.DDPSTATE.LoggedIn.ordinal()) {
                if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - CONNECTION - LoggedIn > launch onLogin");
                onLogin();

            } else if (state == DDPStateSingleton.DDPSTATE.NotLoggedIn.ordinal()) {
                if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - CONNECTION - NotLoggedIn > launch onLogout");
                onLogout();
            }
        } else if (intent.getAction().equals(
                DDPStateSingleton.MESSAGE_SUBUPDATED)) {
            String subscriptionName = bundle.getString(DDPStateSingleton.MESSAGE_EXTRA_SUBNAME);
            String changeType       = bundle.getString(DDPStateSingleton.MESSAGE_EXTRA_CHANGETYPE);
            String docId            = bundle.getString(DDPStateSingleton.MESSAGE_EXTRA_CHANGEID);
            if (TRACE) Log.v("DDPBroadcastReceiver", "onReceive - SUB UPDATED - subscription: " + subscriptionName + " - changeType: " +changeType);
            onSubscriptionUpdate(changeType, subscriptionName, docId);
        }
    }

    /**
     * Override this method to handle subscription update events
     * @param changeType "add", "change", or "remove"
     * @param subscriptionName subscription name (can be different from collection name)
     * @param docId document ID being changed or removed; null if add
     */
    protected void onSubscriptionUpdate(String changeType, String subscriptionName, String docId) {
       if (TRACE) Log.v("DDPBroadcastReceiver", "onSubscriptionUpdate - not implemented" + "changeType = [" + changeType + "], subscriptionName = [" + subscriptionName + "], docId = [" + docId + "]");
    }
    
    /**
     * Override this to hook into the login event
     */
    protected void onLogin() {
        if (TRACE) Log.v("DDPBroadcastReceiver", "onLogin - not implemented");
    }
    
    /**
     * Override this to hook into the logout event
     */
    protected void onLogout() {
        if (TRACE) Log.v("DDPBroadcastReceiver", "onLogout - not implemented");
    }

    /**
     * Override this to hook into what happens when DDP connect happens
     * Default behavior is to feed in the resume token if available
     * @param ddp DDP singleton
     */
    protected void onDDPConnect(DDPStateSingleton ddp) {
        if (TRACE) Log.v("DDPBroadcastReceiver", "onDDPConnect - " + "ddp = [" + ddp + "]");
        if (!ddp.isLoggedIn()) {
            // override this to handle first time connection (usually to subscribe)
            // if we have a login resume token, use it
            String resumeToken = ddp.getResumeToken();
            if (resumeToken != null) {
                ddp.login(resumeToken);
            }
        }
    }

    /**
     * Override this to hook into error display
     * Default behavior is to display the error as a dialog in your application
     * TODO - comment out the AlertDialog builder so you allow the client to decide how to display the error, if he wants to
     * @param title title of error
     * @param msg detail of error
     */
    protected void onError(String title, String msg) {
        if (TRACE) Log.v("DDPBroadcastReceiver", "onError - " + "title = [" + title + "], msg = [" + msg + "]");
        // override this to override default error handling behavior
        /* BB-MOD canceled the default warning to solve the login / logout problem
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setMessage(msg).setTitle(title);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
        //*/
    }
}
