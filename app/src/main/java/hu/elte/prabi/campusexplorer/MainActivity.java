package hu.elte.prabi.campusexplorer;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class MainActivity
    extends AppCompatActivity
    implements GoogleApiClient.ConnectionCallbacks,
               GoogleApiClient.OnConnectionFailedListener,
               LocationListener {

    private final String ACTION_USB_PERMISSION = "hu.elte.prabi.campusexplorer.USB_PERMISSION";
    private final String LOGTAG = "CampusExplorerMainA";
    private Set<Integer> compatibleBoardVendorIds = new HashSet<>();
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private RobotHandlerThread robotHandlerThread;
    private Handler uiHandler;
    private GoogleApiClient gApiClient;
    private LocationRequest locationRequest;

    private void logOnScreen(CharSequence message) {
        TextView logView = (TextView) findViewById(R.id.logView);
        assert logView != null;
        logView.append(message + "\n");
        Log.i(LOGTAG, message.toString());
    }

    @Nullable
    private UsbDevice findFirmataCompatibleUsbDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (compatibleBoardVendorIds.contains(device.getVendorId())) {
                return device;
            }
        }
        return null;
    }

    // Broadcast Receiver to automatically start and stop connection with the robot.
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                if (intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)) {
                    logOnScreen("Requested USB permission, starting control thread...");
                    UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
                    robotHandlerThread =
                            new RobotHandlerThread(uiHandler, context, usbDevice, connection);
                    robotHandlerThread.start();
                } else {
                    logOnScreen("USB permission denied.");
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                logOnScreen("USB device attached.");
                usbDevice = findFirmataCompatibleUsbDevice();
                if (usbDevice != null) {
                    Intent usbPermIntent = new Intent(ACTION_USB_PERMISSION);
                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, usbPermIntent, 0);
                    usbManager.requestPermission(usbDevice, pi);
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                if (robotHandlerThread != null) {
                    robotHandlerThread.terminate();
                    robotHandlerThread = null;
                }
                logOnScreen("Robot disconnected.");
            }
        }
    };

    private void importCompatibleBoardVendorIds() throws XmlPullParserException, IOException {
        XmlResourceParser xmlParser = getResources().getXml(R.xml.device_filter);
        int eventType = xmlParser.next();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && xmlParser.getName().equals("usb-device")) {
                String vendorId = xmlParser.getAttributeValue(null, "vendor-id");
                compatibleBoardVendorIds.add(Integer.parseInt(vendorId));
            }
            eventType = xmlParser.next();
        }
        xmlParser.close();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Fetch compatible board vendor IDs from resource XML.
        try {
            importCompatibleBoardVendorIds();
        }
        catch (XmlPullParserException | IOException e) {
            logOnScreen("Failed to import compatible vendor ids.");
        }

        // Define Handler for receiving messages from RobotHandlerThread.
        uiHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                logOnScreen((CharSequence)msg.obj);
            }
        };

        if (gApiClient == null) {
            // Set up high frequency and high accuracy for location requests.
            locationRequest = new LocationRequest();
            locationRequest.setInterval(1000);
            locationRequest.setFastestInterval(250);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            // Instantiate Google API Client.
            gApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }
        gApiClient.connect();

        // Register IntentFilter for managing USB connection issues.
        usbManager = (UsbManager)
                getSystemService(hu.elte.prabi.campusexplorer.MainActivity.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, filter);
    }

    @Override
    public void onDestroy() {
        if (robotHandlerThread != null) {
            robotHandlerThread.terminate();
            robotHandlerThread = null;
        }
        LocationServices.FusedLocationApi.removeLocationUpdates(gApiClient, this);
        gApiClient.disconnect();
        gApiClient = null;
        unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        // Make sure phone settings are appropriate for navigation.
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        PendingResult<LocationSettingsResult> result =
                LocationServices.SettingsApi.checkLocationSettings(gApiClient, builder.build());
        result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
            @Override
            public void onResult(@NonNull LocationSettingsResult result) {
                final Status status = result.getStatus();
                if (status.getStatusCode() == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                    try {
                        status.startResolutionForResult(MainActivity.this, 0x1);
                    } catch (IntentSender.SendIntentException e) {
                        Log.e(LOGTAG, e.toString());
                    }
                }
            }
        });

        // Check whether permission for accessing fine location is granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
        }
        else {
            requestLocationUpdates();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onLocationChanged(Location location) {
        if (robotHandlerThread != null) {
            robotHandlerThread.registerLocation(location);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdates();
        }
        else {
            logOnScreen("User denied access to location services, app won't work.");
        }
    }

    private void requestLocationUpdates() {
        //noinspection MissingPermission
        LocationServices.FusedLocationApi.requestLocationUpdates(gApiClient, locationRequest, this);
    }
}
