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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Base64;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.keysolutions.ddpclient.DDPClient;
import com.keysolutions.ddpclient.DDPClient.CONNSTATE;
import com.keysolutions.ddpclient.DDPClient.DdpMessageField;
import com.keysolutions.ddpclient.DDPClient.DdpMessageType;
import com.keysolutions.ddpclient.DDPListener;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Base/common handling of DDP state with default handling of collection data as maps
 * Override data update methods to store data into SQLite or other data store
 * @author kenyee
 */
public class DDPStateSingleton extends MeteorAuthCommands
        implements Observer, DDPStateBroadcasts, DDPStateStorage {
    private static final String TAG = "DDPStateSingleton";
    
    // Broadcast Intent info
    public static final String MESSAGE_ERROR                = "ddpclient.ERROR";
    public static final String MESSAGE_CONNECTION           = "ddpclient.CONNECTIONSTATE";
    public static final String MESSAGE_METHODRESUlT         = "ddpclient.METHODRESULT";
    public static final String MESSAGE_LOGINERROR           = "ddpclient.LOGINERROR";
    public static final String MESSAGE_SUBUPDATED           = "ddpclient.SUBUPDATED";
    public static final String MESSAGE_EXTRA_MSG            = "ddpclient.REASON";
    public static final String MESSAGE_EXTRA_STATE          = "ddpclient.STATE";
    public static final String MESSAGE_EXTRA_RESULT         = "ddpclient.RESULT";
    public static final String MESSAGE_EXTRA_METHODID       = "ddpclient.METHODID";
    public static final String MESSAGE_EXTRA_USERID         = "ddpclient.USERID";
    public static final String MESSAGE_EXTRA_USERTOKEN      = "ddpclient.USERTOKEN";
    public static final String MESSAGE_EXTRA_SUBNAME        = "ddpclient.SUBNAME";
    public static final String MESSAGE_EXTRA_CHANGETYPE     = "ddpclient.CHANGETYPE";
    public static final String MESSAGE_EXTRA_CHANGEID       = "ddpclient.CHANGEID";
    // method ID for login
    public static final String METHODID_LOGIN = "login1";
    // resumetoken pref key
    public static final String PREF_DDPINFO = "ddp.info";
    public static final String PREF_RESUMETOKEN = "resume.token";
    
    /** instance of this class because it's a singleton */
    protected static DDPStateSingleton mInstance;
    
    /**
     * connection info for your Meteor server
     */
    protected static String sMeteorServer = "demoparties.meteor.com";
    protected String mMeteorServerHostname = sMeteorServer;
    /**
     * connection info for your Meteor server
     */
    protected static Integer sMeteorPort = 80;
    protected Integer mMeteorPort = sMeteorPort;
    
    /** reference to lower level DDP websocket client */
    protected DDPClient mDDP;

    /** reference to Android application context */
    private Context mContext;
    
    /** current DDP state */
    public enum DDPSTATE {
        NotLoggedIn, Connected, LoggedIn, Closed,
    };

    /** used to track DDP state */
    protected DDPSTATE mDDPState;
    
    /** stores resume token on login */
    private String mResumeToken;
    
    /** stores user ID on login */
    private String mUserId;

    /** stores subscription tracking IDs **/
    private ConcurrentHashMap<String, Boolean> subscriptionsAreReady = new ConcurrentHashMap<String, Boolean>();
    
    /** internal storage for collections */
    // { collectionName,
    //  { docId, {
    //              {fieldName1, fieldValue1},
    //              {fieldName2, fieldValue2},
    //           }
    //  }
    // }
    private final Map<String, Map<String, Map<String,Object>>> mCollections = new ConcurrentHashMap<String, Map<String, Map<String,Object>>>();;
    
    /** Google GSON object for parsing JSON */
    protected final Gson mGSON = new Gson();

    /** Array for mobile login services which sepecifically only return long-lived access tokens **/
    protected final List<String> accessTokenServices = Arrays.asList("facebook");

    /**
     * Connects to default meteor server/port on current machine (not a good idea...use the other method)
     * @param context Android context
     */
    public static void initInstance(Context context) {
        // only called by MyApplication
        initInstance(context, sMeteorServer, sMeteorPort);
    }
    /**
     * Initializes instance to connect to given Meteor server
     * @param context Android context
     * @param meteorServerHostname Meteor hostname/IP
     */
    public static void initInstance(Context context, String meteorServerHostname) {
        // only called by MyApplication
        initInstance(context, meteorServerHostname, sMeteorPort);
    }
    public static void initInstance(Context context, String meteorServer, Integer meteorPort) {
        // only called by MyApplication
        if (mInstance == null) {
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
        this.mContext = context;
        this.mMeteorServerHostname = meteorServer;
        this.mMeteorPort = meteorPort;
        createDDPClient();
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
     * gets the Meteor Server address
     * 
     * @return Meteor Server address
     */
    public String getServerHostname() {
        return mMeteorServerHostname;
    }
    
    /**
     * Sets the Meteor Server It will also restart the connection if there is
     * one already connected
     * 
     * @param meteorServerHostname Meteor hostname/IP
     */
    public void setServer(String meteorServerHostname) {
        if (this.isConnected()) {
            getDDP().disconnect();
        }
        mMeteorServerHostname = meteorServerHostname;
        // autorestart the connection
        connectIfNeeded();
    }

    /**
     * Sets the Meteor Port - default is 80
     * 
     * @return Integer value of the Meteor port
     */
    public Integer getServerPort() {
        return mMeteorPort;
    }

    /**
     * Sets server port (only if you want something besides default of 80)
     * 
     * @param meteorPort
     *            port to set
     */
    public void setServerPort(Integer meteorPort) {
        this.mMeteorPort = meteorPort;
    }
    
    /**
     * Creates a new DDP websocket client (needed for reconnect because we can't reuse it)
     */
    protected void createDDPClient() {
        try {
            mDDP = new DDPClient(getServerHostname(), getServerPort());
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid Websocket URL connecting to " + getServerHostname()
                    + ":" + getServerPort());
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
     * Logs in using access token -- this breaks the current convention,
     * but the method call is dependent on some of this class's variables
     * @param serviceName service name i.e facebook, google
     * @param accessToken short-lived one-time code received, or long-lived access token for Facebook login
     * For some logins, such as Facebook, login with OAuth may only work after customizing the accounts-x packages,
     * until meteor decides to change the packages themselves.
     * use https://github.com/jasper-lu/accounts-facebook-ddp and
     *     https://github.com/jasper-lu/facebook-ddp for reference
     *
     * If an sdk only allows login returns long-lived token, modify your accounts-x package,
     * and add the service to the accessTokenServices list
     */

    public void loginWithOAuth(String serviceName, String accessToken) {
        sendOAuthHTTPRequest(serviceName, accessToken, randomSecret(), new Response.Listener<String>(){
            @Override
            public void onResponse(String response) {
                //json is stored inside an html object in the response
                Matcher matcher = Pattern.compile("<div id=\"config\" style=\"display:none;\">(.*?)</div>").matcher(response);
                matcher.find();
                try {
                    JSONObject jsonResponse = new JSONObject(matcher.group(1));

                    Map options = new HashMap<String, Object>();
                    Map oauth = new HashMap<String, String>();
                    oauth.put("credentialSecret", jsonResponse.get("credentialSecret"));
                    oauth.put("credentialToken", jsonResponse.get("credentialToken"));
                    options.put("oauth", oauth);

                    Object[] methodArgs = new Object[1];
                    methodArgs[0] = options;

                    getDDP().call("login", methodArgs, new DDPListener() {
                        @Override
                        public void onResult(Map<String, Object> jsonFields) {
                            Log.d(TAG, jsonFields.toString());
                            handleLoginResult(jsonFields);
                        }
                    });
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Login with OAuth utilities
     */
    /*
     * Login with OAuth requires the priming of "pending credentials" on the server to receive a secret
     */
    private void sendOAuthHTTPRequest(String serviceName, String accessToken, String credentialToken, Response.Listener listener) {
        RequestQueue queue = Volley.newRequestQueue(mContext);
        String url = "http://" + getServerHostname() + ":" + getServerPort() + "/_oauth/" + serviceName + "/";
        //as far as I know, Facebook is the only one that only returns a long-lived token on mobile login
        String params;
        if (accessTokenServices.contains(serviceName)) {
            params = "?accessToken=" + accessToken + "&state=" + generateState(credentialToken);
        } else {
            params = "?code=" + accessToken + "&state=" + generateState(credentialToken);
        }

        StringRequest request = new StringRequest(Request.Method.GET, url + params, listener, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                error.printStackTrace();
                Log.d(TAG, "If you're getting a weird error, " +
                        "this could be because you haven't configured " +
                        "your server for ddp loginWithOAuth yet. " +
                        "For Facebook login, remove accounts-facebook " +
                        "and add jasperlu:accounts-facebook-ddp instead.");
            }
        });

        queue.add(request);
    }

    /*
     * client-side generation of credential token
     */
    private String randomSecret() {
        byte[] r = new byte[32];
        new Random().nextBytes(r);
        String s = Base64.encodeToString(r, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return s;
    }

    /*
     * generates state and converts to base64 for sending to server
     */
    private String generateState(String credentialToken) {
        JSONObject json = new JSONObject();
        try {
            json.put("credentialToken", credentialToken);
            json.put("loginStyle", "popup");
        }catch(Exception e) {
            Log.d(TAG, e.getMessage());
        }
        Log.d(TAG, json.toString());
        String encoded = Base64.encodeToString(json.toString().getBytes(), Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        return encoded;
    }

    /**
     * Subscribes to specified subscription (note: this can be different from collection)
     * @param subscriptionName name of subscription
     * @param params parameters for subscription function (e.g., doc ID, etc.)
     */
    public void subscribe(final String subscriptionName, Object[] params) {
        // add the subscription to the HashMap with its initial value "false" which says that it is not ready
        subscriptionsAreReady.put(subscriptionName, false);
        
        // subscribe to a Meteor collection with given params
        // test error handling for invalid subscription
        getDDP().subscribe(subscriptionName, params, new DDPListener() {
            @Override
            public void onReady(String id) {
                // mark subscription ready
                subscriptionsAreReady.replace(subscriptionName, true);

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
     * Checks that all subscriptions have sent the "ready" messages if(
     * changeType.equals(DDPClient.DdpMessageType.READY) and
     * (ddp.areAllSubscriptionsReady() == true) ) // everything is ready
     * 
     * @return boolean - true = all subscriptions are Ready - false = some
     *         subscriptions aren't ready
     */
    public boolean areAllSubscriptionsReady() {
        boolean allReady = !(subscriptionsAreReady.containsValue(false));
        return allReady;
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
            String collName = (String) jsonFields.get(DdpMessageField.COLLECTION);
            String docId = (String) jsonFields.get(DdpMessageField.ID);

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
                addDoc(jsonFields, collName, docId);
                // broadcast that subscription has been updated
                broadcastSubscriptionChanged(collName, DdpMessageType.ADDED, docId);
            } else if (msgtype.equals(DdpMessageType.REMOVED)) {
                if (removeDoc(collName, docId)) {
                    // broadcast that subscription has been updated
                    broadcastSubscriptionChanged(collName,
                            DdpMessageType.REMOVED, docId);
                }
            } else if (msgtype.equals(DdpMessageType.CHANGED)) {
                // handle document updates
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

        Map<String, Object> fields;

        if(jsonFields.get(DdpMessageField.FIELDS) == null) {
            fields = new ConcurrentHashMap<>();
        } else {
            fields = (Map<String, Object>) jsonFields.get(DdpMessageField.FIELDS);
        }

        collection.put(docId, fields);

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
     * Gets current Meteor user info
     * @return user Info or null if not logged in
     */
    public Map<String, Object> getUser() {
        //Log.v("DDPStateSingleton", "getDocument - " + "collectionName = [" + collectionName + "], docId = [" + docId + "]");
        Map<String, Object> userFields = DDPStateSingleton.getInstance()
                .getDocument("users", getUserId());
        if (userFields != null) {
            return userFields;
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
    
    /**
     * Calls a Meteor method
     * @param method name of corresponding Meteor method
     * @param params arguments to be passed to the Meteor method
     * @param resultListener DDP command listener for this method call
     * @return command ID
     */
    public int call(String method, Object[] params,
            DDPListener resultListener) {
        return getDDP().call(method, params, resultListener);
    }

    /**
     * Calls Meteor method w/o caring about result callback
     * @param method meteor method name
     * @param params array of string parameters
     * @return command ID
     */
    public int call(String method, Object[] params) {
        return call(method, params, null);
    }
}
