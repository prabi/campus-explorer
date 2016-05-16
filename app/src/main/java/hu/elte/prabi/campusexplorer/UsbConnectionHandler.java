package hu.elte.prabi.campusexplorer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.support.annotation.Nullable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

class UsbConnectionHandler extends BroadcastReceiver {

    static final String USB_PERMISSION = "hu.elte.prabi.campusexplorer.USB_PERMISSION";
    private final String LOGTAG = "UsbConnHandler";
    private final Set<Integer> compatibleBoardVendorIds = new HashSet<>();

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private Robot robot;

    @Nullable
    public Robot getRobot() {
        return robot;
    }

    private void importCompatibleBoardVendorIds(Context context)
            throws XmlPullParserException, IOException {
        XmlResourceParser xmlParser = context.getResources().getXml(R.xml.device_filter);
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

    @Nullable
    private UsbDevice findFirmataCompatibleUsbDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (compatibleBoardVendorIds.contains(device.getVendorId())) {
                return device;
            }
        }
        return null;
    }

    private void requestPermissionIfAppropriate(Context context) {
        usbDevice = findFirmataCompatibleUsbDevice();
        if (usbDevice != null) {
            Intent usbPermIntent = new Intent(USB_PERMISSION);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, usbPermIntent, 0);
            usbManager.requestPermission(usbDevice, pi);
        }
    }

    public UsbConnectionHandler(Context context) {
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        // Fetch compatible board vendor IDs from resource XML.
        try {
            importCompatibleBoardVendorIds(context);
        }
        catch (XmlPullParserException | IOException e) {
            Log.e(LOGTAG, "Failed to import compatible vendor ids.");
        }

        // Check if compatible board is already available.
        requestPermissionIfAppropriate(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(USB_PERMISSION)) {
            if (intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)) {
                Log.i(LOGTAG, "Received USB permission request.");
                UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
                robot = new Robot(usbDevice, connection);
            } else {
                Log.e(LOGTAG, "USB permission denied.");
            }
        }
        else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            Log.i(LOGTAG, "USB device attached.");
            requestPermissionIfAppropriate(context);
        }
        else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
            if (robot != null) {
                robot.terminate();
                robot = null;
            }
            Log.i(LOGTAG, "Robot disconnected.");
        }
    }
}
