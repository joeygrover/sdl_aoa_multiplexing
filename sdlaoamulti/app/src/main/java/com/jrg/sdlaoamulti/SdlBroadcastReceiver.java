package com.jrg.sdlaoamulti;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.smartdevicelink.transport.SdlRouterService;

public class SdlBroadcastReceiver extends com.smartdevicelink.transport.SdlBroadcastReceiver  {

	private static final String TAG = "SdlBroadcastServicer";

	@Override
	public Class<? extends SdlRouterService> defineLocalSdlRouterClass() {
		// TODO Auto-generated method stub
		return com.jrg.sdlaoamulti.SdlRouterService.class;
	}

	@Override
	public void onSdlEnabled(Context context, Intent intent) {
		Log.w(TAG, "SDL has been enabled: " + intent.getAction());
		
	}


	

}
