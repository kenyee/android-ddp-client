Fork info
---------
This is a fork of [kenyees android-ddp-client][https://github.com/kenyee/android-ddp-client]
This Version is still under heavy development but a few improvements over kenyee's version have been added:

### changes to Kenyee's version
- changes to the build.gradle and project.properties
to solve some compilation problems and update the android buildTools and dependencies to the latest versions.
The changes are made to solve [this issue][https://github.com/kenyee/android-ddp-client/issues/1]
- modified the build.gradle to allow a local version of java-ddp-client that also needed to be updated. 
It now connects to my forked version of kenyees java-ddp-client since this has been modified to solve connection problems and updated to DDP protocol v1 for Meteor 1.0
- solved a couple of connection bugs, due to the update to DDP protocol v1


### improvements over Kenyee's version:
- every new doc you want to add to your collections can now implement a superClass MeteorCollectionsDoc 
which has universal utilities across all docs, such as getField, getString, getInteger, getBoolean, getArrayOfObjects
more are coming soon

- implemented ddp.areAllSubscriptionsReady() in DDPStateSingleton to inform of successfull load order on multi-subscriptions situations


Meteor.js Android DDP Client
============================

This library is an Android layer that builds on top of the Java DDP Client.  
It contains an Android-specific DDP state layer that makes it easier to develop a
Meteor.js Android native client.

On Android, instead of using the Java Observer/Listener pattern of the Java
DDP client, a better design pattern is the publish/subscribe 
event pattern because of how UI handling works.
You can do this w/ the native LocalBroadcastManager from the V4 Support Library,
or you can use 3rd party event libraries like 
[GreenRobot's EventBus][https://github.com/greenrobot/EventBus],
[Square's Otto][https://github.com/square/otto], 
or [Mike Burton's RoboGuice][https://github.com/roboguice/roboguice]
(which has event handling as part of its Dependency Injection support).
This library uses LocalBroadcastManager but this behavior can be overridden.

The local Meteor collections are managed in this base implementation
as Map<String,Object> document collections.  
This behavior can be overridden so you can store data into SQLite.

The [MeteorPartiesDDPClient](https://github.com/kenyee/MeteorPartiesDDPClient)
is an example of how to use this library.

Usage
-----
In Eclipse or Android Studio, import the Java DDP Client library into your
project (you'll also need the Eclipse Gradle plugin installed) 
and then import this library.  Add the Java DDP Client library to
your build path.  Once these build, you can add this library to your Android
app's library references.  If you don't need this, you can just add the
javaddpclient.jar and androidddpclient.aar into your Android app's project.  
If you use gradle for builds, you'll see an error message in Eclipse for 
your project until you clean the project.

Once you do that, you can create a class that extends DDPStateSingleton
that will handle your objects. This example is from the Meteor Parties sample app:

    public class MyDDPState extends DDPStateSingleton {
    ...
        @Override
        protected void broadcastSubscriptionChanged(String collectionName,
            String changetype, String docId) {
            if (collectionName.equals("parties")) {
                if (changetype.equals(DdpMessageType.ADDED)) {
                    mParties.put(docId, new Party(docId, (Map<String, Object>) getCollection(collectionName).get(docId)));
                } else if (changetype.equals(DdpMessageType.REMOVED)) {
                    mParties.remove(docId);
                } else if (changetype.equals(DdpMessageType.UPDATED)) {
                    mParties.get(docId).refreshFields();
                }
            }
            // do the broadcast after we've taken care of our parties wrapper
            super.broadcastSubscriptionChanged(collectionName, changetype, docId);
        }
    ...
    }

If you want to use your own data store, you should override the following
methods in MyDDPState: addDoc, updateDoc, removeDoc, and getUserEmail (the
get user email command has to look through your users collection to translate
a user ID to an email address).  These methods are in the DDPStateStorage
interface.  You don't have to override getCollection and getDocument because
you'll need to return what's appropriate for your data store (objects, etc).

If you want to use your own event system, you should override the following
methods in MyDDPState: broadcastConnectionState, broadcastDDPError,
and broadcastSubscriptionChanged.  These methods are in the DDPStateBroadcasts
interface.

In each of your activities that needs to display "live" data, you'll need
to hook in an event/broadcast receiver to receive the broadcasts
and this can be done in the OnResume method.  If you're using a custom
event bus, you have to handle the error, connection state, and document update events.
Here's an example from a Login Activity: 

    protected void onResume() {
        super.onResume();
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // display errors to the user
                Bundle bundle = intent.getExtras();
                showProgress(false);
                if (intent.getAction().equals(MyDDPState.MESSAGE_ERROR)) {
                    String message = bundle.getString(MyDDPState.MESSAGE_EXTRA_MSG);
                    showError("Login Error", message);
                } else if (intent.getAction().equals(MyDDPState.MESSAGE_CONNECTION)) {
                    int state = bundle.getInt(MyDDPState.MESSAGE_EXTRA_STATE);
                    if (state == MyDDPState.DDPSTATE.LoggedIn.ordinal()) {
                        // login complete, so we can close this login activity and go back
                        finish();
                    }
                }
            }
    
        };
        // we want error messages
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver,
                new IntentFilter(MyDDPState.MESSAGE_ERROR));
        // we want connection state change messages so we know we're logged in
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver,
                new IntentFilter(MyDDPState.MESSAGE_CONNECTION));
    }

Remember to unhook the receiver when the activity is suspended in OnPause:

    protected void onPause() {
        super.onPause();             
        if (mReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

Maven Artifact
--------------
This library is in the Maven Central Library hosted by Sonatype.
In Gradle, you can reference it with this in your dependencies:

    compile group: 'com.keysolutions', name: 'android-ddp-client', version: '0.5.7.+'

And in Maven, you can reference it with this:

    <dependency>
      <groupId>com.keysolutions</groupId>
      <artifactId>android-ddp-client</artifactId>
      <version>0.5.7.4</version>
      <type>pom</type>
    </dependency>

The version of the library will match the Meteor.js DDP protocol version with the 
library revision in the last digit (0.5.7.1, 0.5.7.2, etc.).  This version supports
DDP protocol version 0.5.7 (handled by java-ddp-client library).

* 0.5.7.1 uses com.google.android:support-v4:r7
* 0.5.7.2 uses com.android.support:support-v4:13.0.+ and adds getDocument() method
* 0.5.7.3 fix Maven dynamic version syntax
* 0.5.7.4 didn't use subscription parameter in ddpstatesingleton
* 0.5.7.6 fix handling of add/delete field (skipped version 0.5.7.6 to match java-ddp-client update)
  
To-Do
-----
* Move JUnit tests from java-ddp-client to this library?
* Review DDPStateSingleton inheritance...it'd be a lot cleaner if Java supported
multiple inheritance or C#'s partial classes.
