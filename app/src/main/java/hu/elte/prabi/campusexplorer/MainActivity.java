package hu.elte.prabi.campusexplorer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;
import android.widget.Toast;

import name.antonsmirnov.firmata.Firmata;
import name.antonsmirnov.firmata.serial.SerialException;

public class MainActivity extends AppCompatActivity {

    private final String ACTION_USB_PERMISSION = "hu.elte.prabi.campusexplorer.USB_PERMISSION";

    private UsbManager usbManager;
    protected Firmata robot;

    @Nullable
    private UsbDevice findArduinoUsbDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == 0x2341)  // Arduino Vendor ID
            {
                return device;
            }
        }
        return null;
    }

    protected void notifyUser(CharSequence message) {
        TextView logView = (TextView) findViewById(R.id.logView);
        logView.append(message);
        logView.append("\n");
    }

    // Broadcast Receiver to automatically start and stop connection with the robot.
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                if (intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)) {
                    UsbDevice usbDevice = findArduinoUsbDevice();
                    if (usbDevice != null) {
                        UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
                        robot = new Firmata(new FelhrUSBSerialAdapter(usbDevice, connection));
                        notifyUser("Robot connected.");
                    }
                } else {
                    Toast.makeText(context, "USB permission denied.", Toast.LENGTH_SHORT).show();
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                UsbDevice device = findArduinoUsbDevice();
                if (device != null) {
                    Intent usbPermIntent = new Intent(ACTION_USB_PERMISSION);
                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, usbPermIntent, 0);
                    usbManager.requestPermission(device, pi);
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                try {
                    robot.getSerial().stop();
                }
                catch (SerialException e) {
                    notifyUser("Error while stopping USB device.");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(hu.elte.prabi.campusexplorer.MainActivity.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, filter);
    }
}
