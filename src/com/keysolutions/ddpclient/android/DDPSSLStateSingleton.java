package com.keysolutions.ddpclient.android;

import java.net.URISyntaxException;

import javax.net.ssl.TrustManager;

import android.content.Context;
import android.util.Log;

import com.keysolutions.ddpclient.DDPClient;

public class DDPSSLStateSingleton extends DDPStateSingleton{
	
	private static final String TAG = "DDPSSLStateSingleton";
	
	private TrustManager[] trustManagers;

	public DDPSSLStateSingleton(Context context, String meteorServer, Integer meteorPort){
		super(context, meteorServer, meteorPort);
	}
	
	public DDPSSLStateSingleton(Context context, String meteorServer, Integer meteorPort, TrustManager[] trustManagers){
		super(context, meteorServer, meteorPort);
		
		this.trustManagers = trustManagers;
	}

	@Override
	protected void createDDPClient() {
		try {
            mDDP = (trustManagers == null) ? 
            		new DDPClient(getServerHostname(), getServerPort(), true) : new DDPClient(getServerHostname(), getServerPort(), trustManagers);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid Websocket URL connecting to " + getServerHostname()
                    + ":" + getServerPort());
        }
        getDDP().addObserver(this);
        mDDPState = DDPSTATE.NotLoggedIn;
	}
}
