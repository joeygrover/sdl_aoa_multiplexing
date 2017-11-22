/*
 * Copyright (c) 2017 Joey Grover.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of the Joey Grover nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.jrg.sdlaoamulti;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.transport.ITransportListener;
import com.smartdevicelink.transport.MultiUsbTransport;
import com.smartdevicelink.transport.RouterServiceValidator;
import com.smartdevicelink.transport.TransportConstants;
import com.smartdevicelink.transport.USBTransport;
import com.smartdevicelink.transport.USBTransportConfig;
import com.smartdevicelink.transport.enums.TransportType;

public class MainActivity extends AppCompatActivity  implements ITransportListener {
    private static final String TAG = "Sdl Router Activity";
    MultiUsbTransport transport;


    Messenger routerServiceMessenger = null;
    final Messenger clientMessenger = new Messenger(new ClientHandler());

    private ServiceConnection routerConnection;
    boolean isBound = false;

    private void initRouterConnection(){
        routerConnection= new ServiceConnection() {

            public void onServiceConnected(ComponentName className, IBinder service) {
                Log.d(TAG, "Bound to service " + className.toString());
                try {
                    Log.e(TAG, "getInterfaceDescrptor usb" +service.getInterfaceDescriptor());
                } catch (RemoteException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                routerServiceMessenger = new Messenger(service);
                isBound = true;
                //So we just established our connection
                sendConnectionEvent(true);
            }

            public void onServiceDisconnected(ComponentName className) {
                Log.d(TAG, "UN-Bound from service " + className.getClassName());
                routerServiceMessenger = null;
                isBound = false;
                clearUsb();
                finish();
            }
        };
    }

    class ClientHandler extends Handler { //Where we receive messages
        ClassLoader loader = getClass().getClassLoader();
        @Override
        public void handleMessage(Message msg) {

            Bundle bundle = msg.getData();

            if(bundle!=null){
                bundle.setClassLoader(loader);
            }
            switch(msg.what){
                case TransportConstants.ROUTER_REGISTER_ALT_TRANSPORT_RESPONSE:
                    switch(msg.arg1){
                        case TransportConstants.ROUTER_REGISTER_ALT_TRANSPORT_RESPONSE_SUCESS:
                            Log.d(TAG, "Connection is a go");
                            break;
                        default:
                            Log.w(TAG, "Unable to register alt transport, reason: " + msg.arg1);
                    }
                    break;
                case TransportConstants.ROUTER_SEND_PACKET:
                    if(transport!=null){
                        if(transport.getIsConnected()){
                            byte[] packet = bundle.getByteArray(TransportConstants.BYTES_TO_SEND_EXTRA_NAME);
                            int offset = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_OFFSET, 0); //If nothing, start at the begining of the array
                            int count = bundle.getInt(TransportConstants.BYTES_TO_SEND_EXTRA_COUNT, packet.length);  //In case there isn't anything just send the whole packet.
                            if(!transport.write(packet)){
                                Log.e(TAG, "Unable to write bytes");
                            }else{
                                Log.i(TAG, "Wrote out bytes");
                            }
                        }else{
                            Log.w(TAG, "Can't send bytes, transport isn't connected. State = " + transport.getState());
                        }
                    }else{
                        Log.w(TAG, "Can't send bytes, transportis null");
                    }
                    break;
            }
        }
    };

    private void bindToRouter(){
        RouterServiceValidator vlad =new RouterServiceValidator(getBaseContext());
        if(vlad.validate()){
            sendBindingIntent(vlad.getService());
        }else{
            Log.w(TAG, "Unable to bind to router service");
            wakeUpRouterServices();
        }

    }

    private boolean sendBindingIntent(ComponentName name){
        if(routerConnection == null){
            initRouterConnection();
        }
        if(name!=null && name.getPackageName() !=null && name.getClassName() !=null){
            Log.d(TAG, "Sending bind request to " + name.getPackageName() + " - " + name.getClassName());
            Intent bindingIntent = new Intent();
            bindingIntent.setClassName(name.getPackageName(), name.getClassName());//This sets an explicit intent
            //Quickly make sure it's just up and running
            bindingIntent.setAction(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT);
            startService(bindingIntent);
            return getBaseContext().bindService(bindingIntent, routerConnection, Context.BIND_ABOVE_CLIENT);
        }else{

            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initRouterConnection();
        initUsb();

        checkUsbAccessoryIntent("Create");

        Button tv = (Button)findViewById(R.id.bindBtn);
        tv.setOnClickListener(new View.OnClickListener(){

            @Override
            public void onClick(View v) {
                bindToRouter();

            }

        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        if(transport == null){
            initUsb();
        }
        checkUsbAccessoryIntent("Resume");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(transport == null){
            initUsb();
        }
        checkUsbAccessoryIntent("onNewIntent");

    }

    private void checkUsbAccessoryIntent(String sourceAction) {
        final Intent intent = getIntent();
        String action = intent.getAction();
        Log.d(TAG, sourceAction + " with action: " + action);

        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
            Intent usbAccessoryAttachedIntent =
                    new Intent(USBTransport.ACTION_USB_ACCESSORY_ATTACHED);
            usbAccessoryAttachedIntent.putExtra(UsbManager.EXTRA_ACCESSORY,
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY));
            usbAccessoryAttachedIntent
                    .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
                            intent.getParcelableExtra(
                                    UsbManager.EXTRA_PERMISSION_GRANTED));
            sendBroadcast(usbAccessoryAttachedIntent);
        }

        // finish();
    }

    @Override
    protected void onDestroy() {
        sendConnectionEvent(false);
        clearUsb();
        unBindFromRouter();
        clearBroadcastReceiver();
        super.onDestroy();
    }

    private void clearBroadcastReceiver(){
        if(routerReadyReceiver!=null){
            try{
                unregisterReceiver(routerReadyReceiver);
            }catch(Exception e){

            }
        }

    }



    private void initUsb(){
        transport = new MultiUsbTransport(new USBTransportConfig(this.getBaseContext()),this);
        try {
            transport.openConnection();
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }

    public void clearUsb(){
        if(transport!=null){
            transport.disconnect();
            transport = null;
        }
    }
    public void unBindFromRouter(){
        if(isBound && routerConnection!=null){
            try{
                unbindService(routerConnection);
                isBound = false;
                routerConnection = null;
            }catch(Exception e){
                e.printStackTrace();
            }
        }
    }

    public void sendConnectionEvent(boolean connected){
        Log.i(TAG, "attempting to send connection event");
        if(isBound && routerServiceMessenger !=null){
            Message message = Message.obtain();
            message.what = TransportConstants.HARDWARE_CONNECTION_EVENT;
            Bundle bundle = new Bundle();
            if(connected){
                bundle.putBoolean(TransportConstants.HARDWARE_CONNECTED, true);
                bundle.putString(TransportConstants.HARDWARE_CONNECTED, TransportType.USB.name());

            }else{
                bundle.putBoolean(TransportConstants.HARDWARE_DISCONNECTED, false);
                bundle.putString(TransportConstants.HARDWARE_DISCONNECTED, TransportType.USB.name());

            }
            message.setData(bundle);
            message.replyTo = clientMessenger;
            sendMessageToRouterService(message);
        }else{
            Log.w(TAG, "Can't send connection update");
        }
    }

    protected synchronized boolean sendMessageToRouterService(Message message){
        if(message == null){
            Log.w(TAG, "Attempted to send null message");
            return false;
        }
        //Log.i(TAG, "Attempting to send message type - " + message.what);
        if(isBound && routerServiceMessenger !=null){
            try {
                routerServiceMessenger.send(message);
                Log.d(TAG, "Message to sent to router service");
                return true;
            } catch (RemoteException e) {
                if(e instanceof TransactionTooLargeException){
                    e.printStackTrace();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    return sendMessageToRouterService(message);
                }else{
                    //DeadObject, time to kill our connection
                    Log.d(TAG, "Dead object while attempting to send packet");
                    routerServiceMessenger = null;
                    isBound = false;
                    clearUsb();
                    finish();
                    return false;
                }

            }
        }else{
            Log.e(TAG, "Unable to send message to router service. Not bound.");
            return false;
        }
    }

    @Override
    public void onTransportPacketReceived(SdlPacket packet) {
        if(packet!=null && isBound && routerServiceMessenger!=null){
            Message message = Message.obtain();
            message.what = TransportConstants.ROUTER_RECEIVED_PACKET;
            Bundle bundle = new Bundle();
            bundle.putParcelable(TransportConstants.FORMED_PACKET_EXTRA_NAME, packet);
            message.setData(bundle);
            sendMessageToRouterService(message);

        }else{
            Log.e(TAG, "Coundn't forward packet to router service");
        }

    }

    private BroadcastReceiver routerReadyReceiver = new BroadcastReceiver(){

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Router service ready");
            bindToRouter();

        }

    };
    private void wakeUpRouterServices(){
        //Register a receiver to know when the router service is up and running
        registerReceiver(routerReadyReceiver, new IntentFilter(TransportConstants.ALT_TRANSPORT_RECEIVER));

        Intent intent = new Intent(TransportConstants.START_ROUTER_SERVICE_ACTION);
        intent.putExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, true);
        intent.putExtra(TransportConstants.BIND_REQUEST_TYPE_ALT_TRANSPORT, true);
        sendBroadcast(intent);

        Log.i(TAG, "Sent broadcast to wake up router service");
    }

    @Override
    public void onTransportConnected() {
        Log.d(TAG, "USB Connected");
        bindToRouter();

    }

    @Override
    public void onTransportDisconnected(String info) {
        Log.d(TAG, "USB Disconnected");
        sendConnectionEvent(false);
        clearUsb();
        unBindFromRouter();

    }

    @Override
    public void onTransportError(String info, Exception e) {
        Log.d(TAG, "USB Error");
        sendConnectionEvent(false);
        clearUsb();
        unBindFromRouter();

    }
}
