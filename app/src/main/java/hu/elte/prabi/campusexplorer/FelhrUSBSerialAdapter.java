package hu.elte.prabi.campusexplorer;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import java.util.ArrayList;
import java.util.List;

import name.antonsmirnov.firmata.serial.ISerial;
import name.antonsmirnov.firmata.serial.ISerialListener;
import name.antonsmirnov.firmata.serial.SerialException;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

/**
 * Wiring felhr.usbserial into antonsmirnov.firmata
 */
public class FelhrUSBSerialAdapter implements ISerial, UsbSerialInterface.UsbReadCallback {

    protected UsbSerialDevice serialDevice;
    protected boolean initialized = false;
    protected List<ISerialListener> listeners = new ArrayList<>();
    protected int actualOffset = 0;
    protected byte[] inputBuffer = new byte[]{};

    public FelhrUSBSerialAdapter(UsbDevice device, UsbDeviceConnection connection) {
        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);
    }

    @Override
    public void addListener(ISerialListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ISerialListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void start() throws SerialException {
        if (serialDevice != null && serialDevice.open()) {
            serialDevice.setBaudRate(57600);
            serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
            serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
            serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
            serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
            serialDevice.read(this);
            initialized = true;
        } else {
            throw new SerialException(new Exception("Couldn't open serial port."));
        }
    }

    @Override
    public void stop() {
        serialDevice.close();
        initialized = false;
    }

    @Override
    public boolean isStopping() {
        return !initialized;
    }

    @Override
    public int available() {
        return inputBuffer.length - actualOffset;
    }

    @Override
    public void clear() {
        actualOffset = inputBuffer.length;
    }

    @Override
    public int read() throws SerialException {
        if (available() > 0) {
            return inputBuffer[actualOffset];
        }
        else {
            throw new SerialException(new Exception("No byte is available to read."));
        }
    }

    @Override
    public void write(int i) {
        serialDevice.write(new byte[]{(byte) i});
    }

    @Override
    public void write(byte[] bytes) {
        serialDevice.write(bytes);
    }

    @Override
    public void onReceivedData(byte[] bytes) {
        inputBuffer = bytes;
        for (ISerialListener listener : listeners) {
            actualOffset = 0;
            while (available() > 0) {
                listener.onDataReceived(this);
                actualOffset++;
            }
        }
        inputBuffer = new byte[]{};
        actualOffset = 0;
    }

}
