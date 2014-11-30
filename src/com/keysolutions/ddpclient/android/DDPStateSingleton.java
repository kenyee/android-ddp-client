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
import java.util.HashMap;
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
    public final static boolean TRACE = false;		            // for debugging
    
    // Broadcast Intent info
    public static final String MESSAGE_ERROR            = "ddpclient.ERROR";
    public static final String MESSAGE_CONNECTION       = "ddpclient.CONNECTIONSTATE";
    public static final String MESSAGE_METHODRESUlT     = "ddpclient.METHODRESULT";
    public static final String MESSAGE_LOGINERROR       = "ddpclient.LOGINERROR";
    public static final String MESSAGE_SUBUPDATED       = "ddpclient.SUBUPDATED";
    public static final String MESSAGE_EXTRA_MSG        = "ddpclient.REASON";
    public static final String MESSAGE_EXTRA_STATE      = "ddpclient.STATE";
    public static final String MESSAGE_EXTRA_RESULT     = "ddpclient.RESULT";
    public static final String MESSAGE_EXTRA_METHODID   = "ddpclient.METHODID";
    public static final String MESSAGE_EXTRA_USERID     = "ddpclient.USERID";
    public static final String MESSAGE_EXTRA_USERTOKEN  = "ddpclient.USERTOKEN";
    public static final String MESSAGE_EXTRA_SUBNAME    = "ddpclient.SUBNAME";
    public static final String MESSAGE_EXTRA_CHANGETYPE = "ddpclient.CHANGETYPE";
    public static final String MESSAGE_EXTRA_CHANGEID   = "ddpclient.CHANGEID";
    // method ID for login
    public static final String METHODID_LOGIN = "login1";
    // resumetoken pref key
    public static final String PREF_DDPINFO = "ddp.info";
    public static final String PREF_RESUMETOKEN = "resume.token";
    
    /** instance of this class because it's a singleton */
    protected static DDPStateSingleton mInstance;
    
    /**
     * connection info for your Meteor server
     * Override to point to your server - this is kenyees default server
     */
    //protected static final String sMeteorServer = "demoparties.meteor.com";   // original implementation that didn't allow to overwrite the server
    protected static String mMeteorServer = "demoparties.meteor.com";           // BB-MOD default server

    /**
     * connection info for your Meteor server
     * Override to point to your server's port
     */
    // protected static final Integer sMeteorPort = 80;       // original implementation that didn't allow to overwrite it
    protected static Integer mMeteorPort = 80;
    
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

    /**BB-MOD holds the ready state of subscriptions (subscriptionName, true or false) - so that the subscriber can know when to continue with the initiation */
    ConcurrentHashMap subscriptionsAreReady = new ConcurrentHashMap();

    /** internal storage for collections */
    // { collectionName,
    //      { docId, {
    //                  {fieldName1, fieldValue1},
    //                  {fieldName2, fieldValue2},
    //                }
    //      }
    // }
    private final Map<String, Map<String, Map<String,Object>>> mCollections = new ConcurrentHashMap<String, Map<String, Map<String,Object>>>();;
    
    /** Google GSON object for parsing JSON */
    protected final Gson mGSON = new Gson();

    /*BB-MOD OVERLOADING INIT INSTANCE so that you can call it without server and port
    // and it will connecta utomatically to the default server and port
    public static void initInstance(Context context) {
        Log.v("DDPStateSingleton", "initInstance - " + "context = [" + context + "]");
        if (mInstance == null) {
            mInstance = new DDPStateSingleton(context, "", 80);
        }
    }

    public static void initInstance(Context context, String meteorServer) {
        Log.v("DDPStateSingleton", "initInstance - " + "context = [" + context + "], meteorServer = [" + meteorServer + "]");
        if (mInstance == null) {
            mInstance = new DDPStateSingleton(context, meteorServer, 80);
        }
    }
    */

    public static void initInstance(Context context, String meteorServer, Integer meteorPort) {
        Log.v("DDPStateSingleton", "initInstance - " + "context = [" + context + "], meteorServer = [" + meteorServer + "], meteorPort = [" + meteorPort + "]");
        // only called by MyApplication
        if (mInstance == null) {
            // Create the instance
            mInstance = new DDPStateSingleton(context, meteorServer, meteorPort);
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
     * @param meteorServer String containing the meteor server ie: demoparties.meteor.com
     * @param meteorPort Integer of the meteor server port ie: 80
     */
    public DDPStateSingleton(Context context, String meteorServer, Integer meteorPort) {
        if (TRACE) Log.v("DDPStateSingleton","DDPStateSingleton - " + "context = [" + context + "], meteorServer = [" + meteorServer + "], meteorPort = [" + meteorPort + "]");
        this.mContext = context;
        this.mMeteorServer = meteorServer;
        this.mMeteorPort   = meteorPort;
        //if (meteorServer != null) this.setServer(meteorServer);
        //if (meteorPort   != null) this.setServerPort(meteorPort);

        createDDPClient();
        // disable IPv6 if we're running on Android emulator because websocket
        // library doesn't work with it
        if ("google_sdk".equals(Build.PRODUCT)) {
            java.lang.System.setProperty("java.net.preferIPv6Addresses", "false");
            java.lang.System.setProperty("java.net.preferIPv4Stack", "true");
        }
        // kick off a connection before UI starts up
        isConnected();
    }

    /**
     * Creates a new DDP websocket client (needed for reconnect because we can't reuse it)
     */
    public void createDDPClient() {
        if (TRACE) Log.i(TAG, "createDDPClient with server: " + getServer() + "  port: " + getServerPort());
        try {
            //mDDP = new DDPClient(sMeteorServer, sMeteorPort);
            mDDP = new DDPClient(getServer(), getServerPort());
//            Log.i(TAG, "createDDPClient mDDP: " + mDDP);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid Websocket URL connecting to " + getServer()
                    + ":" + getServerPort());
        }
        getDDP().addObserver(this);
        mDDPState = DDPSTATE.NotLoggedIn;
    }
    
    /**
     * Gets Java DDP Client object
     * @return DDP Client
     */
    //protected DDPClient getDDP() {
    // BB-MOD to allow to call mDDP methods like disconnect - TODO verify that you don't need this and change connectIfNeeded
    public DDPClient getDDP() {
        return mDDP;
    }

    /**
     * gets the Meteor Server
     * @return Meteor Server
     */
    public String getServer(){
        return mMeteorServer;
    }

    /**
     * sets the Meteor Server
     * it also restart the connection if there is one already connected
     * @param meteorServer
     */
    public void setServer(String meteorServer) {
        if (TRACE) Log.v("DDPStateSingleton", "setServer - " + "meteorServer = [" + meteorServer + "]");
        if (this.isConnected()) {
            getDDP().disconnect();
            //TODO - verify that the the current connection is closed via logging
            //Log.v("DDPStateSingleton", "setServer - just disconnected server state:" + getDDP().getState());
        }
        mMeteorServer = meteorServer;
        // autorestart the connection
        connectIfNeeded();

    }

    /**
     * sets the Meteor Port - default is 80
     * @return Integer value of the meteor port
     */
    public Integer getServerPort() {
        return mMeteorPort;
    }

    /**
     *
      * @param meteorPort
     */
    public void setServerPort(Integer meteorPort) {
        this.mMeteorPort = meteorPort;
    }



    /**
     * Gets state of DDP connection (including login info which isn't part of DDPClient state)
     * @return DDP connection state
     */
    public DDPSTATE getState() {
        if (TRACE) Log.v("DDPStateSingleton","getState");
        //Log.v("DDPStateSingleton", "getState: " + getDDP().getState());
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
        if (TRACE) Log.w("DDPStateSingleton","connectIfNeeded");
        if (getDDP().getState() == CONNSTATE.Disconnected) {
            Log.i(TAG, "connectIfNeeded - currently disconnected > connect");
            // make connection to Meteor server
            getDDP().connect();
            return true;

        } else if (getDDP().getState() == CONNSTATE.Closed) {
            Log.i(TAG, "connectIfNeeded - currently closed connection > Re-connect");
           // try to reconnect w/ a new connection
            createDDPClient();
            getDDP().connect();
        }
        return false;
    }
    
    /**
     * Whether we're connected to server
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        if (TRACE) Log.v("DDPStateSingleton","testing if isConnected");
        //Log.v("DDPStateSingleton", "isConnected - state:" + getDDP().getState());
        return ((getDDP().getState() != CONNSTATE.Disconnected) 
                && (getDDP().getState() != CONNSTATE.Closed));
    }

    /**
     * Whether we're logged into server
     * @return true if connected, false otherwise
     */
    public boolean isLoggedIn() {
        if (TRACE) Log.v("DDPStateSingleton","isLoggedIn - " + "");
        return mDDPState == DDPSTATE.LoggedIn;
    }

    /**
     * Logs out from server and removes the resume token
     */
    public void logout() {
        if (TRACE) Log.v("DDPStateSingleton","logout");
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
        if (TRACE) Log.v("DDPStateSingleton", "saveResumeToken - " + "token = [" + token + "]");
        SharedPreferences settings = mContext.getSharedPreferences(PREF_DDPINFO, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        //REVIEW: should token be encrypted?
        editor.putString(PREF_RESUMETOKEN, token);
        editor.commit();
        Log.v("DDPStateSingleton", "saveResumeToken - settings" + settings + "  editor: " + editor);
    }

    /**
     * Gets resume token from Android's internal app storage
     * @return resume token or null if not found
     */
    public String getResumeToken() {
        if (TRACE) Log.v("DDPStateSingleton", "getResumeToken");
        // bb - the settings have already registered the token "Ep4E5iJ6jjkNB4wog"
        SharedPreferences settings = mContext.getSharedPreferences(PREF_DDPINFO, Context.MODE_PRIVATE);
        if (TRACE) Log.v("DDPStateSingleton","getResumeToken - settings" + settings + " PREF_RESUMETOKEN:" + settings.getString(PREF_RESUMETOKEN, null) );
        return settings.getString(PREF_RESUMETOKEN, null);
    }

    /**
     * Handles callback from login command
     * @param jsonFields fields from result
     */
    @SuppressWarnings("unchecked")
    public void handleLoginResult(Map<String, Object> jsonFields) {
        if (TRACE) Log.v("DDPStateSingleton", "handleLoginResult - " + "jsonFields = [" + jsonFields + "]");
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
        if (TRACE) Log.v("DDPStateSingleton", "subscribe to " + "subscriptionName = [" + subscriptionName + "], params = [" + params + "]");

        // BB-Mod add the subscription to the HashMap with its initial value "false" which says that it is not ready
        subscriptionsAreReady.put(subscriptionName, false);

        // subscribe to a Meteor collection with given params
        // test error handling for invalid subscription
        getDDP().subscribe(subscriptionName, params, new DDPListener() {
            @Override
            public void onReady(String id) {
                Log.i("DDPStateSingleton", "subscribe > new DDPListener.onReady - subscription: " + subscriptionName + "  -  id: " + id);
                //BB-MOD store that the subscription is ready && check the status
                subscriptionsAreReady.replace(subscriptionName, true);
                areAllSubscriptionsReady();

                // broadcast that subscription has been updated
                broadcastSubscriptionChanged(subscriptionName, DdpMessageType.READY, null);
            }

            @Override
            public void onResult(Map<String, Object> jsonFields) {
                //Log.v("DDPStateSingleton", "subscribe > new DDPListener.onResult - " + "jsonFields = [" + jsonFields + "]");
                String msgtype = (String) jsonFields.get(DDPClient.DdpMessageField.MSG);
                if (msgtype == null) {
                    // ignore {"server_id":"GqrKrbcSeDfTYDkzQ"} web socket msgs
                    return;
                } else if (msgtype.equals(DdpMessageType.ERROR)) {
                    broadcastDDPError((String) jsonFields.get(DdpMessageField.ERRORMSG));
                }
            }
        });
    }

    /**
     * BB-MOD
     * searches if all subscriptions have sent the "ready" messages
     * if( changeType.equals(DDPClient.DdpMessageType.READY) && (ddp.areAllSubscriptionsReady() == true) ) // everything is ready
     * @return boolean - true = all subscriptions are Ready - false = some subscriptions aren't ready
     */
    public boolean areAllSubscriptionsReady () {
        // if there is one which is not ready the contains value returns true, so we reverse it with !
        boolean allReady = !(subscriptionsAreReady.containsValue(false));
        //Log.v("DDPStateSingleton", "areAllSubscriptionsReady: " + allReady);
        return allReady;
    }


    /**
     * Used to notify event system of connection events.
     * Default behavior uses Android's LocalBroadcastManager.
     * Override if you want to use a different eventbus.
     * @param ddpstate current DDP state
     */
    public void broadcastConnectionState(DDPSTATE ddpstate) {
        if (TRACE) Log.i(TAG, "broadcastConnectionState  ddpstate: " + ddpstate + " - ddpstate.ordinal(): "+ddpstate.ordinal());
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MESSAGE_CONNECTION);
        broadcastIntent.putExtra(MESSAGE_EXTRA_STATE, ddpstate.ordinal());
        broadcastIntent.putExtra(MESSAGE_EXTRA_USERID, mUserId);
        broadcastIntent.putExtra(MESSAGE_EXTRA_USERTOKEN, mResumeToken);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(broadcastIntent);
    }

    /**
     * Used to notify event system of error events.
     * Default behavior uses Android's LocalBroadcastManager.
     * Override if you want to use a different eventbus.
     * @param errorMsg error message
     */
    public void broadcastDDPError(String errorMsg) {
        if (TRACE) Log.v("DDPStateSingleton", "broadcastDDPError - " + "errorMsg = [" + errorMsg + "]");
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
    public void broadcastSubscriptionChanged(String subscriptionName, String changetype, String docId) {
        if (TRACE) Log.v("DDPStateSingleton", "broadcastSubscriptionChanged - " + "subscriptionName = [" + subscriptionName + "], changetype = [" + changetype + "], docId = [" + docId + "]");
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
        // Show clearly in the console a new cycle of update from the server
        if (TRACE) {
            //Log.v("DDPStateSingleton", "-----------------------------------");
            //Log.v("DDPStateSingleton", "          new DDP update           ");
            //Log.v("DDPStateSingleton", "-----------------------------------");
            Log.i("DDPStateSingleton", "--------- DDP UPDATE - " + /* "client = [" + client + "],*/ "msg = " + msg);
        }

        // handle subscription updates and other messages that aren't associated with a specific command
        if (msg instanceof Map<?, ?>) {
            Map<String, Object> jsonFields = (Map<String, Object>) msg;
            // handle msg types for DDP server->client msgs:
            // https://github.com/meteor/meteor/blob/master/packages/livedata/DDP.md
            String msgtype = (String) jsonFields.get(DDPClient.DdpMessageField.MSG);
            String collName = (String) jsonFields.get(DdpMessageField.COLLECTION);
            String docId = (String) jsonFields.get(DdpMessageField.ID);
            Log.i("DDPStateSingleton", "update - msgtype: " + msgtype +   "   collName: " + collName + "  docId: "+docId);

            if (msgtype == null) {
                // ignore {"server_id":"GqrKrbcSeDfTYDkzQ"} web socket msgs
                return;

            } else if (msgtype.equals(DdpMessageType.ERROR)) {
                //Log.w("DDPStateSingleton", "update - ERROR" );
                broadcastDDPError((String) jsonFields.get(DdpMessageField.ERRORMSG));

            } else if (msgtype.equals(DdpMessageType.CONNECTED)) {
                Log.v("DDPStateSingleton", "update - CONNECTED mDDPState:" + mDDPState );
                mDDPState = DDPSTATE.Connected; //BB-MOD was commented but is currently set to NotLoggedIn
                broadcastConnectionState(mDDPState);

            } else if (msgtype.equals(DdpMessageType.ADDED)) {
//                Log.v("DDPStateSingleton", "update - ADDED" );
                //String collName = (String) jsonFields.get(DdpMessageField.COLLECTION);
                //String docId = (String) jsonFields.get(DdpMessageField.ID);
                addDoc(jsonFields, collName, docId);
                // broadcast that subscription has been updated
                broadcastSubscriptionChanged(collName, DdpMessageType.ADDED, docId);

            } else if (msgtype.equals(DdpMessageType.REMOVED)) {
//                Log.v("DDPStateSingleton", "update - REMOVED" );
               // String collName = (String) jsonFields.get(DdpMessageField.COLLECTION);
                //String docId = (String) jsonFields.get(DdpMessageField.ID);
                if (removeDoc(collName, docId)) {
                    // broadcast that subscription has been updated
                    broadcastSubscriptionChanged(collName, DdpMessageType.REMOVED, docId);
                }

            } else if (msgtype.equals(DdpMessageType.CHANGED)) {
//                Log.v("DDPStateSingleton", "update - CHANGED" );
                // handle document updates
                //String collName = (String) jsonFields.get(DdpMessageField.COLLECTION);
                //String docId = (String) jsonFields.get(DdpMessageField.ID);
                if (updateDoc(jsonFields, collName, docId)) {
                    // broadcast that subscription has been updated
                    broadcastSubscriptionChanged(collName, DdpMessageType.CHANGED, docId);
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
    public boolean updateDoc(Map<String, Object> jsonFields, String collName, String docId) {
//        Log.v("DDPStateSingleton", "updateDoc - " + "jsonFields = [" + jsonFields + "], collName = [" + collName + "], docId = [" + docId + "]");
        if (mCollections.containsKey(collName)) {
            Map<String, Map<String,Object>> collection = mCollections.get(collName);
            Map<String, Object> doc = (Map<String, Object>) collection
                    .get(docId);
            if (doc != null) {
                // take care of field updates
                Map<String, Object> fields = (Map<String, Object>) jsonFields.get(DdpMessageField.FIELDS);

                if (fields != null) {
                    for (Map.Entry<String, Object> field : fields.entrySet()) {
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
            Log.w(TAG, "Received invalid changed msg for collection " + collName);
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
//        Log.v("DDPStateSingleton", "removeDoc - " + "collName = [" + collName + "], docId = [" + docId + "]");
        if (mCollections.containsKey(collName)) {
            // remove IDs from collection
            Map<String, Map<String,Object>> collection = mCollections.get(collName);
            //Log.v(TAG, "Removed doc: " + docId);
            collection.remove(docId);
            return true;
        } else {
            //Log.w(TAG, "Received invalid removed msg for collection " + collName);
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
    public void addDoc(Map<String, Object> jsonFields, String collName, String docId) {
//        Log.v("DDPStateSingleton", "addDoc - " + "jsonFields = [" + jsonFields + "], collName = [" + collName + "], docId = [" + docId + "]");
        if (!mCollections.containsKey(collName)) {
            // add new collection
            //Log.v(TAG, "Added collection " + collName);
            mCollections.put(collName, new ConcurrentHashMap<String, Map<String,Object>>());
        }
        Map<String, Map<String,Object>> collection = mCollections.get(collName);
        collection.put(docId, (Map<String, Object>) jsonFields.get(DdpMessageField.FIELDS));
        //Log.v(TAG, "Added docid " + docId + " to collection " + collName);
    }

    /**
     * Gets local Meteor collection
     * @param collectionName collection name
     * @return collection as a Map or null if not found
     */
    public Map<String, Map<String,Object>> getCollection(String collectionName) {
        // return specified collection Map which is indexed by document ID
        //Log.v("DDPStateSingleton", "getCollection - " + "collectionName = [" + collectionName + "]  mCollections: " +mCollections + "  mCollections.get("+collectionName+"):"+mCollections.get(collectionName));
        return mCollections.get(collectionName);
    }
    
    /**
     * Gets a document out of a collection
     * @param collectionName collection name
     * @param docId document ID
     * @return null if not found or a collection of the document's fields
     */
    public Map<String, Object> getDocument(String collectionName, String docId) {
        //Log.v("DDPStateSingleton", "getDocument - " + "collectionName = [" + collectionName + "], docId = [" + docId + "]");
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
        //Log.v("DDPStateSingleton", "getUserId mUserId: " + mUserId);
        return mUserId;
    }
    
    /**
     * Gets email address for user
     * @param userId user ID
     * @return email address for that user (lookup via users collection)
     */
    @SuppressWarnings("unchecked")
    public String getUserEmail(String userId) {
        //Log.v("DDPStateSingleton", "getUserEmail - " + "userId = [" + userId + "]");
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


    //////////////////////////////////   BB-MOD Exposes to everybody the possibility to call a Meteor method like Meteor.call

    /**
     * Copied from DDPClient
     * Call a meteor method with the supplied parameters
     *
     * @param method name of corresponding Meteor method
     * @param params arguments to be passed to the Meteor method
     * @param resultListener DDP command listener for this method call
     *
     * TODO - example with the listener
     * MyDDP.getInstance().meteorCall('MeteorServerMethodName', new Object[]{param1, param2,...})
     */
    public int meteorCall(String method, Object[] params, DDPListener resultListener) {
//        Log.v("DDPStateSingleton", "meteorCall - " + "method = [" + method + "], params = [" + params + "], resultListener = [" + resultListener + "]");
        return getDDP().call(method, params, resultListener);
    }

    public int meteorCall(String method, Object[] params) {
        return meteorCall(method, params, null);
    }

}
