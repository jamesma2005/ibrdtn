package de.tubs.ibr.dtn.ping;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import de.tubs.ibr.dtn.api.Block;
import de.tubs.ibr.dtn.api.Bundle;
import de.tubs.ibr.dtn.api.Bundle.ProcFlags;
import de.tubs.ibr.dtn.api.BundleID;
import de.tubs.ibr.dtn.api.DTNClient;
import de.tubs.ibr.dtn.api.DTNClient.Session;
import de.tubs.ibr.dtn.api.DTNIntentService;
import de.tubs.ibr.dtn.api.DataHandler;
import de.tubs.ibr.dtn.api.GroupEndpoint;
import de.tubs.ibr.dtn.api.Node;
import de.tubs.ibr.dtn.api.Registration;
import de.tubs.ibr.dtn.api.ServiceNotAvailableException;
import de.tubs.ibr.dtn.api.SessionDestroyedException;
import de.tubs.ibr.dtn.api.SingletonEndpoint;
import de.tubs.ibr.dtn.api.TransferMode;
import de.tubs.ibr.dtn.streaming.DtnStreamReceiver;
import de.tubs.ibr.dtn.streaming.DtnStreamTransmitter;
import de.tubs.ibr.dtn.streaming.Frame;
import de.tubs.ibr.dtn.streaming.MediaType;
import de.tubs.ibr.dtn.streaming.StreamFilter;
import de.tubs.ibr.dtn.streaming.StreamId;

public class PingService extends DTNIntentService {
    
    // This TAG is used to identify this class (e.g. for debugging)
    private static final String TAG = "PingService";
    
    // mark a specific bundle as delivered
    public static final String MARK_DELIVERED_INTENT = "de.tubs.ibr.dtn.example.MARK_DELIVERED";
    
    // process a status report
    public static final String REPORT_DELIVERED_INTENT = "de.tubs.ibr.dtn.example.REPORT_DELIVERED";
    
    // this intent send out a PING message
    public static final String PING_INTENT = "de.tubs.ibr.dtn.example.PING";
    
    public static final String STREAM_START_INTENT = "de.tubs.ibr.dtn.example.STREAM_START";
    public static final String STREAM_STOP_INTENT = "de.tubs.ibr.dtn.example.STREAM_STOP";
    public static final String STREAM_SWITCH_GROUP = "de.tubs.ibr.dtn.example.STREAM_SWITCH";
    public static final String UNREGISTER = "de.tubs.ibr.dtn.example.UNREGISTER";

    // indicates updated data to other components
    public static final String DATA_UPDATED = "de.tubs.ibr.dtn.example.DATA_UPDATED";
    
    // group EID of this app
    public static final GroupEndpoint PING_GROUP_EID = new GroupEndpoint("dtn://broadcast.dtn/ping");
    public static final GroupEndpoint STREAM_GROUP1_EID = new GroupEndpoint("dtn://broadcast.dtn/streaming1");
    public static final GroupEndpoint STREAM_GROUP2_EID = new GroupEndpoint("dtn://broadcast.dtn/streaming2");
    
    private GroupEndpoint mStreamGroup = null;
    
    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();
    
    // The communication with the DTN service is done using the DTNClient
    private DTNClient.Session mSession = null;
    
    private DtnStreamReceiver mReceiver = null;
    private DtnStreamTransmitter mTransmitter = null;
    
    private boolean mDestroySessionOnExit = false;
    
    // Hold the last ping result
    private Double mLastMeasurement = 0.0;
    
    // Hold the start time of the last ping
    private Long mStart = 0L;
    
    ScheduledExecutorService mExecutor = null;
    Future<?> mStreamJob = null;
    
    public PingService() {
        super(TAG);
    }
    
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
        public PingService getService() {
            return PingService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    
    public Double getLastMeasurement() {
        return mLastMeasurement;
    }
    
    private String getLocalEndpoint() {
        return getClient().getEndpoint();
    }
    
    public PendingIntent getSelectNeighborIntent() {
		// get pending intent for neighbor list
		return getClient().getSelectNeighborIntent();
    }
    
    public List<Node> getNeighbors() {
		// query all neighbors
		List<Node> ret = getClient().getNeighbors();

		return (ret != null) ? ret : new LinkedList<Node>();
    }
    
    private void doPing(SingletonEndpoint destination) {
        // create a new bundle
        Bundle b = new Bundle();
        
        // set the destination of the bundle
        b.setDestination(destination);
        
        // limit the lifetime of the bundle to 60 seconds
        b.setLifetime(60L);
        
        // set status report requests for bundle reception
        b.set(ProcFlags.REQUEST_REPORT_OF_BUNDLE_RECEPTION, true);
        
        // set destination for status reports
        b.setReportto(SingletonEndpoint.ME);
        
        // generate some payload
        String payload = "Hello World";

        try {
            // store the current time
            mStart = System.nanoTime();
            
            // send the bundle
            BundleID ret = mSession.send(b, payload.getBytes());
            
            if (ret == null)
            {
                Log.e(TAG, "could not send the message");
            }
            else
            {
                Log.d(TAG, "Bundle sent, BundleID: " + ret.toString());
            }
        } catch (SessionDestroyedException e) {
            Log.e(TAG, "could not send the message", e);
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        
        if (de.tubs.ibr.dtn.Intent.RECEIVE.equals(action))
        {
            // Received bundles from the DTN service here
            try {
                // We loop here until no more bundles are available
                // (queryNext() returns false)
                while (mSession.queryNext());
            } catch (SessionDestroyedException e) {
                Log.e(TAG, "Can not query for bundle", e);
            }
        }
        else if (MARK_DELIVERED_INTENT.equals(action))
        {
            // retrieve the bundle ID of the intent
            BundleID bundleid = intent.getParcelableExtra("bundleid");
            
            try {
                // mark the bundle ID as delivered
            	mSession.delivered(bundleid);
            } catch (Exception e) {
                Log.e(TAG, "Can not mark bundle as delivered.", e);
            }
        }
        else if (REPORT_DELIVERED_INTENT.equals(action))
        {
            // retrieve the source of the status report
            SingletonEndpoint source = intent.getParcelableExtra("source");
            
            // retrieve the bundle ID of the intent
            BundleID bundleid = intent.getParcelableExtra("bundleid");
            
            Log.d(TAG, "Status report received for " + bundleid.toString() + " from " + source.toString());
        }
        else if (PING_INTENT.equals(action))
        {
            // retrieve the ping destination
            SingletonEndpoint destination = new SingletonEndpoint(intent.getStringExtra("destination"));
            
            // send out the ping
            doPing(destination);
        }
        else if (STREAM_START_INTENT.equals(action))
        {
            Log.d(TAG, "create stream");
            
            // create DTN stream
            mTransmitter = new DtnStreamTransmitter(PingService.this, mSession);
            mTransmitter.connect(mStreamGroup, MediaType.BINARY, null);
            mTransmitter.setLifetime(10);
            
            mStreamJob = mExecutor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "send stream packet");
                    try {
                        mTransmitter.write("Hello World!".getBytes());
                    } catch (InterruptedException e) {
                        Log.d(TAG, "interrupted while sending", e);
                    } catch (IOException e) {
                        Log.d(TAG, "error while sending", e);
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
        else if (STREAM_STOP_INTENT.equals(action))
        {
            if (mStreamJob != null) {
                mStreamJob.cancel(false);
                mStreamJob = null;
                
                try {
                    mTransmitter.close();
                } catch (InterruptedException e) {
                    Log.d(TAG, "interrupted while closing", e);
                } catch (IOException e) {
                    Log.d(TAG, "error while closing", e);
                }
            }
        }
        else if (STREAM_SWITCH_GROUP.equals(action))
        {
            try {
            	mSession.leave(mStreamGroup);
    
                if (STREAM_GROUP2_EID.equals(mStreamGroup)) {
                    // set current stream group
                    mStreamGroup = STREAM_GROUP1_EID;
                } else {
                    // set current stream group
                    mStreamGroup = STREAM_GROUP2_EID;
                }
                
                mSession.join(mStreamGroup);
            } catch (SessionDestroyedException e) {
                Log.e(TAG, "session destroyed", e);
            }
        }
        else if (UNREGISTER.equals(action))
        {
        	mDestroySessionOnExit = true;
        }
    }
    
    DtnStreamReceiver.StreamListener mStreamListener = new DtnStreamReceiver.StreamListener() {
        @Override
        public void onInitial(StreamId id, MediaType type, byte[] data) {
            Log.d(TAG, "stream initiated: " + id);
        }

        @Override
        public void onFrameReceived(StreamId id, Frame frame) {
            Log.d(TAG, "stream packet received: " + id);
            try {
                Log.d(TAG, "payload: " + new String(frame.data, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // hm? UTF-8 not supported?
            }
        }

        @Override
        public void onFinish(StreamId id) {
            Log.d(TAG, "stream closed: " + id);
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // get new executor
        mExecutor = Executors.newScheduledThreadPool(1);
        
        // create registration with "ping" as endpoint
        // if the EID of this device is "dtn://device" then the
        // address of this app will be "dtn://device/ping"
        Registration registration = new Registration("ping");
        
        // additionally join a group
        registration.add(PING_GROUP_EID);
        registration.add(STREAM_GROUP1_EID);

        // set current stream group
        mStreamGroup = STREAM_GROUP1_EID;
        
        try {
            // initialize the connection to the DTN service
            initialize(registration);
            Log.d(TAG, "Connection to DTN service established.");
        } catch (ServiceNotAvailableException e) {
            // The DTN service has not been found
            Log.e(TAG, "DTN service unavailable. Is IBR-DTN installed?", e);
        } catch (SecurityException e) {
            // The service has not been found
            Log.e(TAG, "The app has no permission to access the DTN service. It is important to install the DTN service first and then the app.", e);
        }
    }

    @Override
    public void onDestroy() {
        if (mDestroySessionOnExit) mSession.destroy();
        
        if (mStreamJob != null) {
            mStreamJob.cancel(false);
            mStreamJob = null;
            
            try {
                mTransmitter.close();
            } catch (InterruptedException e) {
                Log.d(TAG, "interrupted while closing", e);
            } catch (IOException e) {
                Log.d(TAG, "error while closing", e);
            }
        }
        
        // shutdown executor
        mExecutor.shutdown();
        
        super.onDestroy();
    }

    /**
     * This data handler is used to process incoming bundles
     */
    private DataHandler mDataHandler = new DataHandler() {

        private Bundle mBundle = null;

        @Override
        public void startBundle(Bundle bundle) {
            // store the bundle header locally
            mBundle = bundle;
        }

        @Override
        public void endBundle() {
            // complete bundle received
            BundleID received = new BundleID(mBundle);
            
            // stop measurement and store result
            long diffTime = System.nanoTime() - mStart;
            mLastMeasurement = Double.valueOf(diffTime) / 1000000.0;
            
            // mark the bundle as delivered
            Intent i = new Intent(PingService.this, PingService.class);
            i.setAction(MARK_DELIVERED_INTENT);
            i.putExtra("bundleid", received);
            startService(i);
            
            // free the bundle header
            mBundle = null;
            
            // notify other components of the updated value
            Intent updatedIntent = new Intent(DATA_UPDATED);
            updatedIntent.putExtra("bundleid", received);
            sendBroadcast(updatedIntent);
        }
        
        @Override
        public TransferMode startBlock(Block block) {
            // we are only interested in payload blocks (type = 1)
            if (block.type == 1) {
                // return SIMPLE mode to received the payload as "payload()" calls
                return TransferMode.SIMPLE;
            } else {
                // return NULL to discard the payload of this block
                return TransferMode.NULL;
            }
        }

        @Override
        public void endBlock() {
            // nothing to do here.
        }

        @Override
        public ParcelFileDescriptor fd() {
            // This method is used to hand-over a file descriptor to the
            // DTN service. We do not need the method here and always return
            // null.
            return null;
        }

        @Override
        public void payload(byte[] data) {
            // payload is received here
            Log.d(TAG, "payload received: " + data);
        }

        @Override
        public void progress(long offset, long length) {
            // if payload is written to a file descriptor, the progress
            // will be announced here
            Log.d(TAG, offset + " of " + length + " bytes received");
        }
    };

    @Override
    protected void onSessionConnected(Session session) {
        Log.d(TAG, "Session connected");
        mSession = session;
        
        try {
            // list all registered endpoints
           for (GroupEndpoint group : session.getGroups()) {
                Log.d(TAG, "Group: " + group);
            }
        } catch (SessionDestroyedException e) {
            Log.e(TAG, "can not get group list", e);
        }
        
        // create streaming endpoint with own data handler as fallback
        mReceiver = new DtnStreamReceiver(PingService.this, session, mStreamListener, mDataHandler);
        
        StreamFilter filter = new StreamFilter() {
            @Override
            public boolean onHandleStream(Bundle bundle) {
                return bundle.getDestination().toString().contains("stream");
            }
        };
        
        // set filter to decide with bundles are handles as stream
        mReceiver.setFilter(filter);
        
        String localeid = getLocalEndpoint();
        if (localeid != null) {
            // notify other components of the updated EID
            Intent i = new Intent(DATA_UPDATED);
            i.putExtra("localeid", localeid);
            sendBroadcast(i);
        }
	}

	@Override
	protected void onSessionDisconnected() {
        Log.d(TAG, "Session disconnected");
        
        // release stream endpoint
        if (mReceiver != null) {
            mReceiver.release();
        }
	}
}
