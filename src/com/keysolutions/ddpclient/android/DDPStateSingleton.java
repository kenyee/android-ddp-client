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

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ConcurrentHashMap;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.gson.Gson;
import com.keysolutions.ddpclient.DDPClient;
import com.keysolutions.ddpclient.DDPClient.CONNSTATE;
import com.keysolutions.ddpclient.DDPClient.DdpMessageField;
import com.keysolutions.ddpclient.DDPClient.DdpMessageType;
import com.keysolutions.ddpclient.DDPListener;

/**
 * Base/common handling of DDP state with default handling of collection data as maps
 * Override data update methods to store data into SQLite or other data store
 * @author kenyee
 */
public class DDPStateSingleton extends MeteorAuthCommands
        implements Observer, DDPStateBroadcasts, DDPStateStorage {
    private static final String TAG = "DDPStateSingleton";
    
    // Broadcast Intent info
    public static final String MESSAGE_ERROR = "ddpclient.ERROR";
    public static final String MESSAGE_CONNECTION = "ddpclient.CONNECTIONSTATE";
    public static final String MESSAGE_METHODRESUlT = "ddpclient.METHODRESULT";
    public static final String MESSAGE_LOGINERROR = "ddpclient.LOGINERROR";
    public static final String MESSAGE_SUBUPDATED = "ddpclient.SUBUPDATED";
    public static final String MESSAGE_EXTRA_MSG = "ddpclient.REASON";
    public static final String MESSAGE_EXTRA_STATE = "ddpclient.STATE";
    public static final String MESSAGE_EXTRA_RESULT = "ddpclient.RESULT";
    public static final String MESSAGE_EXTRA_METHODID = "ddpclient.METHODID";
    public static final String MESSAGE_EXTRA_USERID = "ddpclient.USERID";
    public static final String MESSAGE_EXTRA_USERTOKEN = "ddpclient.USERTOKEN";
    public static final String MESSAGE_EXTRA_SUBNAME = "ddpclient.SUBNAME";
    public static final String MESSAGE_EXTRA_CHANGETYPE = "ddpclient.CHANGETYPE";
    public static final String MESSAGE_EXTRA_CHANGEID = "ddpclient.CHANGEID";
    // method ID for login
    public static final String METHODID_LOGIN = "login1";
    // resumetoken pref key
    public static final String PREF_DDPINFO = "ddp.info";
    public static final String PREF_RESUMETOKEN = "resume.token";
    
    /** instance of this class because it's a singleton */
    protected static DDPStateSingleton mInstance;
    
    /**
     * connection info for your Meteor server
     * Override to point to your server
     */
    protected static final String sMeteorServer = "demoparties.meteor.com";
    /**
     * connection info for your Meteor server
     * Override to point to your server's port
     */
    protected static final Integer sMeteorPort = 80;
    
    /** reference to lower level DDP websocket client */
    protected DDPClient mDDP;

    /** reference to Android application context */
    private Context mContext;
    
    /** current DDP state */
    public enum DDPSTATE {
        NotLoggedIn, Connected, LoggedIn, Closed,
    };

    /** used to track DDP state */
    private DDPSTATE mDDPState;
    
    /** stores resume token on login */
    private String mResumeToken;
    
    /** stores user ID on login */
    private String mUserId;
    
    /** internal storage for collections */
    private final Map<String, Map<String, Map<String,Object>>> mCollections = new ConcurrentHashMap<String, Map<String, Map<String,Object>>>();;
    
    /** Google GSON object for parsing JSON */
    protected final Gson mGSON = new Gson();

    public static void initInstance(Context context) {
        // only called by MyApplication
        if (mInstance == null) {
            // Create the instance
            mInstance = new DDPStateSingleton(context);
        }
    }

    /**
     * Gets an instance of this class
     * @return only instance of this class since it's a singleton
     */
    public static DDPStateSingleton getInstance() {
        return mInstance;
    }

    /**
     * Constructor for class (hidden because this is a singleton)
     * @param context Android application context
     */
    protected DDPStateSingleton(Context context) {
        this.mContext = context;
        createDDPCLient();
        // disable IPv6 if we're running on Android emulator because websocket
        // library doesn't work with it
        if ("google_sdk".equals(Build.PRODUCT)) {
            java.lang.System.setProperty("java.net.preferIPv6Addresses",
                    "false");
            java.lang.System.setProperty("java.net.preferIPv4Stack", "true");
        }
        // kick off a connection before UI starts up
        isConnected();
    }

    /**
     * Creates a new DDP websocket client (needed for reconnect because we can't reuse it)
     */
    public void createDDPCLient() {
        try {
            mDDP = new DDPClient(sMeteorServer, sMeteorPort);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid Websocket URL connecting to " + sMeteorServer
                    + ":" + sMeteorPort);
        }
        getDDP().addObserver(this);
        mDDPState = DDPSTATE.NotLoggedIn;
    }
    
    /**
     * Gets Java DDP Client object
     * @return DDP Client
     */
    protected DDPClient getDDP() {
        return mDDP;
    }

    /**
     * Gets state of DDP connection (including login info which isn't part of DDPClient state)
     * @return DDP connection state
     */
    public DDPSTATE getState() {
        // used by the UI to figure out what the current DDP connection state is
        if (getDDP().getState() == CONNSTATE.Closed) {
            mDDPState = DDPSTATE.Closed;
        }
        return mDDPState;
    }

    /**
     * Requests DDP connect if needed
     * @return true if connect was issue, otherwise false
     */
    public boolean connectIfNeeded() {
        if (getDDP().getState() == CONNSTATE.Disconnected) {
            // make connection to Meteor server
            getDDP().connect();
            return true;
        } else if (getDDP().getState() == CONNSTATE.Closed) {
           // try to reconnect w/ a new connection
            createDDPCLient();
            getDDP().connect();
        }
        return false;
    }
    
    /**
     * Whether we're connected to server
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return ((getDDP().getState() != CONNSTATE.Disconnected) 
                && (getDDP().getState() != CONNSTATE.Closed));
    }

    /**
     * Whether we're logged into server
     * @return true if connected, false otherwise
     */
    public boolean isLoggedIn() {
        return mDDPState == DDPSTATE.LoggedIn;
    }

    /**
     * Logs out from server and removes the resume token
     */
    public void logout() {
        saveResumeToken(null);
        mDDPState = DDPSTATE.NotLoggedIn;
        mUserId = null;
        //REVIEW: do we need to cut websocket connection?
        broadcastConnectionState(mDDPState);
    }
    
    /**
     * Saves resume token to Android's internal app storage
     * @param token resume token
     */
    public void saveResumeToken(String token) {
        SharedPreferences settings = mContext.getSharedPreferences(PREF_DDPINFO, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        //REVIEW: should token be encrypted?
        editor.putString(PREF_RESUMETOKEN, token);
        editor.commit();
    }

    /**
     * Gets resume token from Android's internal app storage
     * @return resume token or null if not found
     */
    public String getResumeToken() {
        SharedPreferences settings = mContext.getSharedPreferences(PREF_DDPINFO, Context.MODE_PRIVATE);
        return settings.getString(PREF_RESUMETOKEN, null);
    }

    /**
     * Handles callback from login command
     * @param jsonFields fields from result
     */
    @SuppressWarnings("unchecked")
    public void handleLoginResult(Map<String, Object> jsonFields) {
        if (jsonFields.containsKey("result")) {
            Map<String, Object> result = (Map<String, Object>) jsonFields
                    .get(DdpMessageField.RESULT);
            mResumeToken = (String) result.get("token");
            saveResumeToken(mResumeToken);
            mUserId = (String) result.get("id");
            mDDPState = DDPSTATE.LoggedIn;
            broadcastConnectionState(mDDPState);
        } else if (jsonFields.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) jsonFields
                    .get(DdpMessageField.ERROR);
            broadcastDDPError((String) error.get("message"));
        }
    }


    /**
     * Subscribes to specified subscription (note: this can be different from collection)
     * @param subscriptionName name of subscription
     * @param params parameters for subscription function (e.g., doc ID, etc.)
     */
    public void subscribe(final String subscriptionName, Object[] params) {
        // subscribe to a Meteor collection with given params
        // test error handling for invalid subscription
        getDDP().subscribe(subscriptionName, params, new DDPListener() {
            @Override
            public void onReady(String id) {
                // broadcast that subscription has been updated
                broadcastSubscriptionChanged(subscriptionName,
                        DdpMessageType.READY, null);
            }

            @Override
            public void onResult(Map<String, Object> jsonFields) {
                String msgtype = (String) jsonFields
                        .get(DDPClient.DdpMessageField.MSG);
                if (msgtype == null) {
                    // ignore {"server_id":"GqrKrbcSeDfTYDkzQ"} web socket msgs
                    return;
                } else if (msgtype.equals(DdpMessageType.ERROR)) {
                    broadcastDDPError((String) jsonFields
                            .get(DdpMessageField.ERRORMSG));
                }
            }
        });
    }

    /**
     * Used to notify event system of connection events.
     * Default behavior uses Android's LocalBroadcastManager.
     * Override if you want to use a different eventbus.
     * @param ddpstate current DDP state
     */
    public void broadcastConnectionState(DDPSTATE ddpstate) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MESSAGE_CONNECTION);
        broadcastIntent.putExtra(MESSAGE_EXTRA_STATE, ddpstate.ordinal());
        broadcastIntent.putExtra(MESSAGE_EXTRA_USERID, mUserId);
        broadcastIntent.putExtra(MESSAGE_EXTRA_USERTOKEN, mResumeToken);
        LocalBroadcastManager.getInstance(mContext)
                .sendBroadcast(broadcastIntent);
    }

    /**
     * Used to notify event system of error events.
     * Default behavior uses Android's LocalBroadcastManager.
     * Override if you want to use a different eventbus.
     * @param errorMsg error message
     */
    public void broadcastDDPError(String errorMsg) {
        // let core know there was an error subscribing to a collection
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MESSAGE_ERROR);
        broadcastIntent.putExtra(MESSAGE_EXTRA_MSG, errorMsg);
        LocalBroadcastManager.getInstance(mContext)
                .sendBroadcast(broadcastIntent);
    }

    /**
     * Used to notify event system of subscription change events.
     * Default behavior uses Android's LocalBroadcastManager.
     * Override if you want to use a different eventbus.
     * @param subscriptionName subscription name (this can be different from collection name)
     * @param changetype "change", "add" or "remove"
     * @param docId document ID of change/remove or null if add
     */
    public void broadcastSubscriptionChanged(String subscriptionName,
            String changetype, String docId) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MESSAGE_SUBUPDATED);
        broadcastIntent.putExtra(MESSAGE_EXTRA_SUBNAME, subscriptionName);
        broadcastIntent.putExtra(MESSAGE_EXTRA_CHANGETYPE, changetype);
        broadcastIntent.putExtra(MESSAGE_EXTRA_CHANGEID, docId);        
        LocalBroadcastManager.getInstance(mContext)
                .sendBroadcast(broadcastIntent);
    }

    /**
     * handles callbacks from DDP client websocket callbacks
     */
    @SuppressWarnings("unchecked")
    @Override
    public void update(Observable client, Object msg) {
        // handle subscription updates and othre messages that aren't associated
        // w/ a specific command
        if (msg instanceof Map<?, ?>) {
            Map<String, Object> jsonFields = (Map<String, Object>) msg;
            // handle msg types for DDP server->client msgs:
            // https://github.com/meteor/meteor/blob/master/packages/livedata/DDP.md
            String msgtype = (String) jsonFields
                    .get(DDPClient.DdpMessageField.MSG);

            if (msgtype == null) {
                // ignore {"server_id":"GqrKrbcSeDfTYDkzQ"} web socket msgs
                return;
            } else if (msgtype.equals(DdpMessageType.ERROR)) {
                broadcastDDPError((String) jsonFields
                        .get(DdpMessageField.ERRORMSG));
            } else if (msgtype.equals(DdpMessageType.CONNECTED)) {
                mDDPState = DDPSTATE.Connected;
                broadcastConnectionState(mDDPState);
            } else if (msgtype.equals(DdpMessageType.ADDED)) {
                String collName = (String) jsonFields
                        .get(DdpMessageField.COLLECTION);
                String docId = (String) jsonFields.get(DdpMessageField.ID);
                addDoc(jsonFields, collName, docId);
                // broadcast that subscription has been updated
                broadcastSubscriptionChanged(collName, DdpMessageType.ADDED, docId);
            } else if (msgtype.equals(DdpMessageType.REMOVED)) {
                String collName = (String) jsonFields
                        .get(DdpMessageField.COLLECTION);
                String docId = (String) jsonFields.get(DdpMessageField.ID);
                if (removeDoc(collName, docId)) {
                    // broadcast that subscription has been updated
                    broadcastSubscriptionChanged(collName,
                            DdpMessageType.REMOVED, docId);
                }
            } else if (msgtype.equals(DdpMessageType.CHANGED)) {
                // handle document updates
                String collName = (String) jsonFields
                        .get(DdpMessageField.COLLECTION);
                String docId = (String) jsonFields.get(DdpMessageField.ID);
                if (updateDoc(jsonFields, collName, docId)) {
                    // broadcast that subscription has been updated
                    broadcastSubscriptionChanged(collName,
                            DdpMessageType.CHANGED, docId);
                }
            }
        }
    }

    /**
     * Handles updating a document in a collection.
     * Override if you want to use your own collection data store.
     * @param jsonFields fields for document
     * @param collName collection name
     * @param docId documement ID for update
     * @return true if changed; false if document not found
     */
    @SuppressWarnings("unchecked")
    public boolean updateDoc(Map<String, Object> jsonFields, String collName,
            String docId) {
        if (mCollections.containsKey(collName)) {
            Map<String, Map<String,Object>> collection = mCollections.get(collName);
            Map<String, Object> doc = (Map<String, Object>) collection
                    .get(docId);
            if (doc != null) {
                // take care of field updates
                Map<String, Object> fields = (Map<String, Object>) jsonFields
                        .get(DdpMessageField.FIELDS);
                if (fields != null) {
                    for (Map.Entry<String, Object> field : fields
                            .entrySet()) {
                        String fieldname = field.getKey();
                        doc.put(fieldname, field.getValue());
                    }
                }
                // take care of clearing fields
                List<String> clearfields = ((List<String>) jsonFields.get(DdpMessageField.CLEARED));
                if (clearfields != null) {
                    for (String fieldname : clearfields) {
                        if (doc.containsKey(fieldname)) {
                            doc.remove(fieldname);
                        }
                    }
                }
                return true;
            }
        } else {
            Log.w(TAG, "Received invalid changed msg for collection "
                    + collName);
        }
        return false;
    }

    /**
     * Handles deleting a document in a collection.
     * Override if you want to use your own collection data store.
     * @param collName collection name
     * @param docId document ID
     * @return true if doc was deleted, false otherwise
     */
    public boolean removeDoc(String collName, String docId) {
        if (mCollections.containsKey(collName)) {
            // remove IDs from collection
            Map<String, Map<String,Object>> collection = mCollections.get(collName);
            Log.v(TAG, "Removed doc: " + docId);
            collection.remove(docId);
            return true;
        } else {
            Log.w(TAG, "Received invalid removed msg for collection "
                    + collName);
            return false;
        }
    }

    /**
     * Handles adding a document to collection.
     * Override if you want to use your own collection data store.
     * @param jsonFields fields for document
     * @param collName collection name
     * @param docId document ID
     */
    @SuppressWarnings("unchecked")
    public void addDoc(Map<String, Object> jsonFields, String collName,
            String docId) {
        if (!mCollections.containsKey(collName)) {
            // add new collection
            Log.v(TAG, "Added collection " + collName);
            mCollections.put(collName, new ConcurrentHashMap<String, Map<String,Object>>());
        }
        Map<String, Map<String,Object>> collection = mCollections.get(collName);
        collection.put(docId, (Map<String, Object>) jsonFields.get(DdpMessageField.FIELDS));
        Log.v(TAG, "Added docid " + docId + " to collection " + collName);
    }

    /**
     * Gets local Meteor collection
     * @param collectionName collection name
     * @return collection as a Map or null if not found
     */
    public Map<String, Map<String,Object>> getCollection(String collectionName) {
        // return specified collection Map which is indexed by document ID
        return mCollections.get(collectionName);
    }
    
    /**
     * Gets a document out of a collection
     * @param collectionName collection name
     * @param docId document ID
     * @return null if not found or a collection of the document's fields
     */
    public Map<String, Object> getDocument(String collectionName, String docId) {
        Map<String, Map<String,Object>> docs = DDPStateSingleton.getInstance()
                .getCollection(collectionName);
        if (docs != null) {
            return docs.get(docId);
        }
        return null;
    }
    
    /**
     * Gets current Meteor user ID
     * @return user ID or null if not logged in
     */
    public String getUserId() {
        return mUserId;
    }
    
    /**
     * Gets email address for user
     * @param userId user ID
     * @return email address for that user (lookup via users collection)
     */
    @SuppressWarnings("unchecked")
    public String getUserEmail(String userId) {
        if (userId == null) {
            return null;
        }
        // map userId to email
        // NOTE: this gets convoluted if they use OAuth logins because the email
        // field is in the service!
        Map<String, Object> user = getDocument("users", userId); 
        String email = userId;
        if (user != null) {
            ArrayList<Map<String, String>> emails = (ArrayList<Map<String, String>>) user
                    .get("emails");
            if ((emails != null) && (emails.size() > 0)) {
                // get first email address
                Map<String, String> emailFields = emails.get(0);
                email = emailFields.get("address");
            }
        }
        return email;
    }
}
