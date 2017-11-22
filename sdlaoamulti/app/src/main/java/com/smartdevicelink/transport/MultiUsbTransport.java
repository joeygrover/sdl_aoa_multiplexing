package com.smartdevicelink.transport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.exception.SdlExceptionCause;
import com.smartdevicelink.protocol.SdlPacket;
import com.smartdevicelink.trace.SdlTrace;
import com.smartdevicelink.trace.enums.InterfaceActivityDirection;
import com.smartdevicelink.transport.enums.TransportType;
import com.smartdevicelink.util.DebugTool;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by Joey Grover on 11/22/17.
 */

public class MultiUsbTransport extends SdlTransport{
    // Boolean to monitor if the transport is in a disconnecting state
    private boolean _disconnecting = false;
    /**
     * Broadcast action: sent when a USB accessory is attached.
     *
     * UsbManager.EXTRA_ACCESSORY extra contains UsbAccessory object that has
     * been attached.
     */
    public static final String ACTION_USB_ACCESSORY_ATTACHED =
            "com.smartdevicelink.USB_ACCESSORY_ATTACHED";
    /**
     * String tag for logging.
     */
    private static final String TAG = USBTransport.class.getSimpleName();
    /**
     * Key for SdlTrace.
     */
    private static final String SDL_LIB_TRACE_KEY =
            "42baba60-eb57-11df-98cf-0800200c9a66";
    /**
     * Broadcast action: sent when the user has granted access to the USB
     * accessory.
     */
    private static final String ACTION_USB_PERMISSION =
            "com.smartdevicelink.USB_PERMISSION";
    /**
     * Manufacturer name of the accessory we want to connect to. Must be the
     * same as in accessory_filter.xml to work properly.
     */
    private final static String ACCESSORY_MANUFACTURER = "SDL";
    /**
     * Model name of the accessory we want to connect to. Must be the same as
     * in accessory_filter.xml to work properly.
     */
    private final static String ACCESSORY_MODEL = "Core";
    /**
     * Version of the accessory we want to connect to. Must be the same as in
     * accessory_filter.xml to work properly.
     */
    private final static String ACCESSORY_VERSION = "1.0";
    /**
     * Prefix string to indicate debug output.
     */
    private static final String DEBUG_PREFIX = "DEBUG: ";
    /**
     * String to prefix exception output.
     */
    private static final String EXCEPTION_STRING = " Exception String: ";
    /**
     * Broadcast receiver that receives different USB-related intents: USB
     * accessory connected, disconnected, and permission granted.
     */
    private final BroadcastReceiver mUSBReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logD("USBReceiver Action: " + action);

            UsbAccessory accessory =
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (accessory != null) {
                if (ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                    logI("Accessory " + accessory + " attached");
                    if (isAccessorySupported(accessory)) {
                        connectToAccessory(accessory);
                    } else {
                        logW("Attached accessory is not supported!");
                    }
                } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED
                        .equals(action)) {
                    logI("Accessory " + accessory + " detached");
                    final String msg = "USB accessory has been detached";
                    disconnect(msg, new SdlException(msg,
                            SdlExceptionCause.SDL_USB_DETACHED));
                } else if (ACTION_USB_PERMISSION.equals(action)) {
                    boolean permissionGranted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (permissionGranted) {
                        logI("Permission granted for accessory " + accessory);
                        openAccessory(accessory);
                    } else {
                        final String msg =
                                "Permission denied for accessory " + accessory;
                        logW(msg);
                        disconnect(msg, new SdlException(msg,
                                SdlExceptionCause.SDL_USB_PERMISSION_DENIED));
                    }
                }
            } else {
                logW("Accessory is null");
            }
        }
    };
    /**
     * USB config object.
     */
    private USBTransportConfig mConfig = null;
    /**
     * Current state of transport.
     *
     * Use setter and getter to access it.
     */
    private State mState = State.IDLE;
    /**
     * Current accessory the transport is working with if any.
     */
    private UsbAccessory mAccessory = null;
    /**
     * FileDescriptor that owns the input and output streams. We have to keep
     * it, otherwise it will be garbage collected and the streams will become
     * invalid.
     */
    private ParcelFileDescriptor mParcelFD = null;
    /**
     * Data input stream to read data from USB accessory.
     */
    private InputStream mInputStream = null;
    /**
     * Data output stream to write data to USB accessory.
     */
    private OutputStream mOutputStream = null;
    /**
     * Thread that connects and reads data from USB accessory.
     *
     * @see USBTransportReader
     */
    private Thread mReaderThread = null;

    /**
     * Constructs the USBTransport instance.
     *
     * @param usbTransportConfig Config object for the USB transport
     * @param transportListener  Listener that gets notified on different
     *                           transport events
     */
    public MultiUsbTransport(USBTransportConfig usbTransportConfig,
                        ITransportListener transportListener) {
        super(transportListener);
        this.mConfig = usbTransportConfig;
        registerReciever();
    }

    /**
     * Returns the current state of transport.
     *
     * @return Current state of transport
     */
    public State getState() {
        return this.mState;
    }

    /**
     * Changes current state of transport.
     *
     * @param state New state
     */
    private void setState(State state) {
        logD("Changing state " + this.mState + " to " + state);
        this.mState = state;
    }

    /**
     * Sends the array of bytes over USB.
     *
     * @param msgBytes Array of bytes to send
     * @return true if the bytes are sent successfully
     */
    public boolean write(byte[] msgBytes){
        boolean result = false;
        final State state = getState();
        switch (state) {
            case CONNECTED:
                if (mOutputStream != null) {
                    try {
                        mOutputStream.write(msgBytes, 0, msgBytes.length);
                        result = true;

                        logI("Bytes successfully sent");
                        SdlTrace.logTransportEvent(TAG + ": bytes sent",
                                null, InterfaceActivityDirection.Transmit,
                                msgBytes, 0, msgBytes.length,
                                SDL_LIB_TRACE_KEY);
                    } catch (IOException e) {
                        final String msg = "Failed to send bytes over USB";
                        logW(msg, e);
                        handleTransportError(msg, e);
                    }
                } else {
                    final String msg =
                            "Can't send bytes when output stream is null";
                    logW(msg);
                    handleTransportError(msg, null);
                }
                break;

            default:
                logW("Can't send bytes from " + state + " state");
                break;
        }

        return result;
    }

    @Override
    protected boolean sendBytesOverTransport(SdlPacket packet) {
        byte[] msgBytes = packet.constructPacket();
        logD("SendBytes: array size " + msgBytes.length + ", offset " + 0 +
                ", length " + msgBytes.length);

        boolean result = false;
        final State state = getState();
        switch (state) {
            case CONNECTED:
                if (mOutputStream != null) {
                    try {
                        mOutputStream.write(msgBytes, 0, msgBytes.length);
                        result = true;

                        logI("Bytes successfully sent");
                        SdlTrace.logTransportEvent(TAG + ": bytes sent",
                                null, InterfaceActivityDirection.Transmit,
                                msgBytes, 0, msgBytes.length,
                                SDL_LIB_TRACE_KEY);
                    } catch (IOException e) {
                        final String msg = "Failed to send bytes over USB";
                        logW(msg, e);
                        handleTransportError(msg, e);
                    }
                } else {
                    final String msg =
                            "Can't send bytes when output stream is null";
                    logW(msg);
                    handleTransportError(msg, null);
                }
                break;

            default:
                logW("Can't send bytes from " + state + " state");
                break;
        }

        return result;
    }


    public void registerReciever()
    {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        getContext().registerReceiver(mUSBReceiver, filter);
    }

    /**
     * Opens a USB connection if not open yet.
     *
     * @throws SdlException
     */
    @Override
    public void openConnection() throws SdlException {
        final State state = getState();
        switch (state) {
            case IDLE:
                synchronized (this) {
                    logI("openConnection()");
                    setState(State.LISTENING);
                }

                logD("Registering receiver");
                try {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(ACTION_USB_ACCESSORY_ATTACHED);
                    filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
                    filter.addAction(ACTION_USB_PERMISSION);
                    getContext().registerReceiver(mUSBReceiver, filter);
                    initializeAccessory();
                } catch (Exception e) {
                    String msg = "Couldn't start opening connection";
                    logE(msg, e);
                    throw new SdlException(msg, e,
                            SdlExceptionCause.SDL_CONNECTION_FAILED);
                }

                break;

            default:
                logW("openConnection() called from state " + state +
                        "; doing nothing");
                break;
        }
    }

    /**
     * Closes the USB connection if open.
     */
    @Override
    public void disconnect() {
        disconnect(null, null);
    }

    /**
     * Asks the reader thread to stop while it's possible. If it's blocked on
     * read(), there is no way to stop it except for physical USB disconnect.
     */
    //@Override
    public void stopReading() {
        DebugTool.logInfo("USBTransport: stop reading requested, doing nothing");
        // TODO - put back stopUSBReading(); @see <a href="https://adc.luxoft.com/jira/browse/SmartDeviceLink-3450">SmartDeviceLink-3450</a>
    }

    @SuppressWarnings("unused")
    private void stopUSBReading() {
        final State state = getState();
        switch (state) {
            case CONNECTED:
                logI("Stopping reading");
                synchronized (this) {
                    stopReaderThread();
                }
                break;

            default:
                logW("Stopping reading called from state " + state +
                        "; doing nothing");
                break;
        }
    }

    /**
     * Actually asks the reader thread to interrupt.
     */
    private void stopReaderThread() {
        if (mReaderThread != null) {
            logI("Interrupting USB reader");
            mReaderThread.interrupt();
            // don't join() now
            mReaderThread = null;
        } else {
            logD("USB reader is null");
        }
    }

    /**
     * Closes the USB connection from inside the transport with some extra info.
     *
     * @param msg Disconnect reason message, if any
     * @param ex  Disconnect exception, if any
     */
    private void disconnect(String msg, Exception ex) {

        // If already disconnecting, return
        if (_disconnecting) {
            // No need to recursively call
            return;
        }
        _disconnecting = true;

        mConfig.setUsbAccessory(null);

        final State state = getState();
        switch (state) {
            case LISTENING:
            case CONNECTED:
                synchronized (this) {
                    logI("Disconnect from state " + getState() + "; message: " +
                            msg + "; exception: " + ex);
                    setState(State.IDLE);

                    SdlTrace.logTransportEvent(TAG + ": disconnect", null,
                            InterfaceActivityDirection.None, null, 0,
                            SDL_LIB_TRACE_KEY);

                    stopReaderThread();

                    if (mAccessory != null) {
                        if (mOutputStream != null) {
                            try {
                                mOutputStream.close();
                                mOutputStream = null;
                            } catch (IOException e) {
                                logW("Can't close output stream", e);
                                mOutputStream = null;
                            }
                        }
                        if (mInputStream != null) {
                            try {
                                mInputStream.close();
                                mInputStream = null;
                            } catch (IOException e) {
                                logW("Can't close input stream", e);
                                mInputStream = null;
                            }
                        }
                        if (mParcelFD != null) {
                            try {
                                mParcelFD.close();
                                mParcelFD = null;
                            } catch (IOException e) {
                                logW("Can't close file descriptor", e);
                                mParcelFD = null;
                            }
                        }

                        mAccessory = null;
                    }
                }

                logD("Unregistering receiver");
                try {
                    getContext().unregisterReceiver(mUSBReceiver);
                } catch (IllegalArgumentException e) {
                    logW("Receiver was already unregistered", e);
                }

                String disconnectMsg = (msg == null ? "" : msg);
                if (ex != null) {
                    disconnectMsg += ", " + ex.toString();
                }

                if (ex == null) {
                    // This disconnect was not caused by an error, notify the
                    // proxy that the transport has been disconnected.
                    logI("Disconnect is correct. Handling it");
                    handleTransportDisconnected(disconnectMsg);
                } else {
                    // This disconnect was caused by an error, notify the proxy
                    // that there was a transport error.
                    logI("Disconnect is incorrect. Handling it as error");
                    handleTransportError(disconnectMsg, ex);
                }
                break;

            default:
                logW("Disconnect called from state " + state +
                        "; doing nothing");
                break;
        }
        _disconnecting = false;
    }

    /**
     * Returns the type of the transport.
     *
     * @return TransportType.USB
     * @see com.smartdevicelink.transport.enums.TransportType
     */
    @Override
    public TransportType getTransportType() {
        return TransportType.USB;
    }

    /**
     * Looks for an already connected compatible accessory and connect to it.
     */
    private void initializeAccessory() {
        UsbAccessory acc =  mConfig.getUsbAccessory();

        if (!mConfig.getQueryUsbAcc()  && acc == null){
            logI("Query for accessory is disabled and accessory in config was null.");
            return;
        }
        logI("Looking for connected accessories");

        if( acc == null || !isAccessorySupported(acc)){ //Check to see if our config included an accessory and that it is supported. If not, see if there are any other accessories connected.
            UsbManager usbManager = getUsbManager();
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (accessories != null) {
                logD("Found total " + accessories.length + " accessories");
                for (UsbAccessory accessory : accessories) {
                    if (isAccessorySupported(accessory)) {
                        acc = accessory;
                        break;
                    }
                }
            } else {
                logI("No connected accessories found");
                return;
            }
        }
        connectToAccessory(acc);
    }

    /**
     * Checks if the specified connected USB accessory is what we expect.
     *
     * @param accessory Accessory to check
     * @return true if the accessory is right
     */
    public static boolean isAccessorySupported(UsbAccessory accessory) {
        boolean manufacturerMatches =
                ACCESSORY_MANUFACTURER.equals(accessory.getManufacturer());
        boolean modelMatches = ACCESSORY_MODEL.equals(accessory.getModel());
        boolean versionMatches =
                ACCESSORY_VERSION.equals(accessory.getVersion());
        return manufacturerMatches && modelMatches && versionMatches;
    }

    /**
     * Attempts to connect to the specified accessory.
     *
     * If the permission is already granted, opens the accessory. Otherwise,
     * requests permission to use it.
     *
     * @param accessory Accessory to connect to
     */
    private void connectToAccessory(UsbAccessory accessory) {
        final State state = getState();
        switch (state) {
            case LISTENING:
                UsbManager usbManager = getUsbManager();
                if (usbManager.hasPermission(accessory)) {
                    logI("Already have permission to use " + accessory);
                    openAccessory(accessory);
                } else {
                    logI("Requesting permission to use " + accessory);

                    PendingIntent permissionIntent = PendingIntent
                            .getBroadcast(getContext(), 0,
                                    new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(accessory, permissionIntent);
                }

                break;

            default:
                logW("connectToAccessory() called from state " + state +
                        "; doing nothing");
        }
    }

    /**
     * Returns the UsbManager to use with accessories.
     *
     * @return System UsbManager
     */
    private UsbManager getUsbManager() {
        return (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
    }

    /**
     * Opens a connection to the accessory.
     *
     * When this function is called, the permission to use it must have already
     * been granted.
     *
     * @param accessory Accessory to open connection to
     */
    private void openAccessory(UsbAccessory accessory) {
        final State state = getState();
        switch (state) {
            case LISTENING:
                synchronized (this) {
                    logI("Opening accessory " + accessory);
                    mAccessory = accessory;

                    mReaderThread = new Thread(new USBTransportReader());
                    mReaderThread.setDaemon(true);
                    mReaderThread
                            .setName(USBTransportReader.class.getSimpleName());
                    mReaderThread.start();

                    // Initialize the SiphonServer
                    if (SiphonServer.getSiphonEnabledStatus()) {
                        SiphonServer.init();
                    }
                }

                break;

            default:
                logW("openAccessory() called from state " + state +
                        "; doing nothing");
        }
    }

    /**
     * Logs the string and the throwable with ERROR level.
     *
     * @param s  string to log
     * @param tr throwable to log
     */
    private void logE(String s, Throwable tr) {
        DebugTool.logError(s, tr);
    }

    /**
     * Logs the string with WARN level.
     *
     * @param s string to log
     */
    private void logW(String s) {
        DebugTool.logWarning(s);
    }

    /**
     * Logs the string and the throwable with WARN level.
     *
     * @param s  string to log
     * @param tr throwable to log
     */
    private void logW(String s, Throwable tr) {
        StringBuilder res = new StringBuilder(s);
        if (tr != null) {
            res.append(EXCEPTION_STRING);
            res.append(tr.toString());
        }
        logW(res.toString());
    }

    /**
     * Logs the string with INFO level.
     *
     * @param s string to log
     */
    private void logI(String s) {
        DebugTool.logInfo(s);
    }

    /**
     * Logs the string with DEBUG level.
     *
     * @param s string to log
     */
    private void logD(String s) {
        // DebugTool doesn't support DEBUG level, so we use INFO instead
        DebugTool.logInfo(DEBUG_PREFIX + s);
    }

    /**
     * Returns Context to communicate with the OS.
     *
     * @return current context to be used by the USB transport
     */
    private Context getContext() {
        return mConfig.getUSBContext();
    }

    /**
     * Possible states of the USB transport.
     */
    private enum State {
        /**
         * Transport initialized; no connections.
         */
        IDLE,

        /**
         * USB accessory not attached; SdlProxy wants connection as soon as
         * accessory is attached.
         */
        LISTENING,

        /**
         * USB accessory attached; permission granted; data IO in progress.
         */
        CONNECTED
    }

    /**
     * Internal task that connects to and reads data from a USB accessory.
     *
     * Since the class has to have access to the parent class' variables,
     * synchronization must be taken in consideration! For now, all access
     * to variables of USBTransport must be surrounded with
     * synchronized (USBTransport.this) { … }
     */
    private class USBTransportReader implements Runnable {
        /**
         * String tag for logging inside the task.
         */
        private final String TAG = USBTransportReader.class.getSimpleName();

        SdlPsm psm;

        /**
         * Checks if the thread has been interrupted.
         *
         * @return true if the thread has been interrupted
         */
        private boolean isInterrupted() {
            return Thread.interrupted();
        }

        /**
         * Entry function that is called when the task is started. It attempts
         * to connect to the accessory, then starts a read loop until
         * interrupted.
         */
        @Override
        public void run() {
            logD("USB reader started!");
            psm = new SdlPsm();
            psm.reset();
            if (connect()) {
                readFromTransport();
            }

            logD("USB reader finished!");
        }

        /**
         * Attemps to open connection to USB accessory.
         *
         * @return true if connected successfully
         */
        private boolean connect() {
            if (isInterrupted()) {
                logI("Thread is interrupted, not connecting");
                return false;
            }

            final State state = getState();
            switch (state) {
                case LISTENING:

                    synchronized (MultiUsbTransport.this) {
                        try {
                            mParcelFD =
                                    getUsbManager().openAccessory(mAccessory);
                        } catch (Exception e) {
                            final String msg =
                                    "Have no permission to open the accessory";
                            logE(msg, e);
                            disconnect(msg, e);
                            return false;
                        }
                        if (mParcelFD == null) {
                            if (isInterrupted()) {
                                logW("Can't open accessory, and thread is interrupted");
                            } else {
                                logW("Can't open accessory, disconnecting!");
                                String msg = "Failed to open USB accessory";
                                disconnect(msg, new SdlException(msg,
                                        SdlExceptionCause.SDL_CONNECTION_FAILED));
                            }
                            return false;
                        }
                        FileDescriptor fd = mParcelFD.getFileDescriptor();
                        mInputStream = new FileInputStream(fd);
                        mOutputStream = new FileOutputStream(fd);
                    }

                    logI("Accessory opened!");

                    synchronized (MultiUsbTransport.this) {
                        setState(State.CONNECTED);
                        handleTransportConnected();
                    }
                    break;

                default:
                    logW("connect() called from state " + state +
                            ", will not try to connect");
                    return false;
            }

            return true;
        }

        /**
         * Continuously reads data from the transport's input stream, blocking
         * when no data is available.
         */
        private void readFromTransport() {
            final int READ_BUFFER_SIZE = 4096;
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int bytesRead;
            // byte input;
            boolean stateProgress = false;

            // read loop
            while (!isInterrupted()) {
                try {
                    if (mInputStream == null)
                        continue;

                    bytesRead = mInputStream.read(buffer);
                    if (bytesRead == -1) {
                        if (isInterrupted()) {
                            logI("EOF reached, and thread is interrupted");
                        } else {
                            logI("EOF reached, disconnecting!");
                            disconnect("EOF reached", null);
                        }
                        return;
                    }
                } catch (IOException e) {
                    if (isInterrupted()) {
                        logW("Can't read data, and thread is interrupted", e);
                    } else {
                        logW("Can't read data, disconnecting!", e);
                        disconnect("Can't read data from USB", e);
                    }
                    return;
                }

                logD("Read " + bytesRead + " bytes");
                //FIXME SdlTrace.logTransportEvent(TAG + ": read bytes", null,
                //        InterfaceActivityDirection.Receive, buffer, bytesRead,
                //        SDL_LIB_TRACE_KEY);

                if (isInterrupted()) {
                    logI("Read some data, but thread is interrupted");
                    return;
                }
                byte input;
                for(int i=0;i<bytesRead; i++){
                    input=buffer[i];
                    stateProgress = psm.handleByte(input);
                    if(!stateProgress){//We are trying to weed through the bad packet info until we get something
                        //Log.w(TAG, "Packet State Machine did not move forward from state - "+ psm.getState()+". PSM being Reset.");
                        psm.reset();
                        buffer = new byte[READ_BUFFER_SIZE];
                    }

                    if(psm.getState() == SdlPsm.FINISHED_STATE){
                        synchronized (MultiUsbTransport.this) {
                            //Log.d(TAG, "Packet formed, sending off");
                            handleReceivedPacket((SdlPacket)psm.getFormedPacket());
                        }
                        //We put a trace statement in the message read so we can avoid all the extra bytes
                        psm.reset();
                        buffer = new byte[READ_BUFFER_SIZE]; //FIXME just do an array copy and send off

                    }
                }
            }
        }

        // Log functions

        private void logD(String s) {
            DebugTool.logInfo(DEBUG_PREFIX + s);
        }

        private void logI(String s) {
            DebugTool.logInfo(s);
        }

        private void logW(String s) {
            DebugTool.logWarning(s);
        }

        private void logW(String s, Throwable tr) {
            StringBuilder res = new StringBuilder(s);
            if (tr != null) {
                res.append(EXCEPTION_STRING);
                res.append(tr.toString());
            }
            logW(res.toString());
        }

        private void logE(String s, Throwable tr) {
            DebugTool.logError(s, tr);
        }
    }

    @Override
    public String getBroadcastComment() {
        // TODO Auto-generated method stub
        return null;
    }

}
