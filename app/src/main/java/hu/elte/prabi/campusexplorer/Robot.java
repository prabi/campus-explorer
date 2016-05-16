package hu.elte.prabi.campusexplorer;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import name.antonsmirnov.firmata.Firmata;
import name.antonsmirnov.firmata.IFirmata;
import name.antonsmirnov.firmata.message.ServoConfigMessage;
import name.antonsmirnov.firmata.message.SetPinModeMessage;
import name.antonsmirnov.firmata.serial.SerialException;

class Robot {

    private final String LOGTAG = "RobotControl";

    private Firmata firmata;

    static class ControlParams {
        public int speed;
        public int turning;
        public ControlParams(int speed, int turning) {
            this.speed = speed;
            this.turning = turning;
        }
    }

    public Robot(UsbDevice device, UsbDeviceConnection connection) {
        firmata = new Firmata(new FelhrUSBSerialAdapter(device, connection));

        // Log unhandled bytes received from USB Serial.
        firmata.addListener(new IFirmata.StubListener() {
            @Override
            public void onUnknownByteReceived(int byteValue) {
                Log.d(LOGTAG, "Received unexpected byte: " + (char)byteValue);
            }
        });

        // Start USB Serial communication and initialize robot control.
        try {
            firmata.getSerial().start();
            firmata.send(new SetPinModeMessage(8, SetPinModeMessage.PIN_MODE.SERVO.getMode()));
            firmata.send(new SetPinModeMessage(9, SetPinModeMessage.PIN_MODE.SERVO.getMode()));
        }
        catch (SerialException e) {
            Log.e(LOGTAG, e.toString());
        }

        // Set the robot's speed to 0 and turn its wheels to look straight forward.
        steerRobot(new ControlParams(0, 0));
    }

    public void terminate() {
        try {
            firmata.getSerial().stop();
        }
        catch (SerialException e) {
            Log.e(LOGTAG, e.toString());
        }
    }

    public void steerRobot(ControlParams ctrlp) {
        try {
            firmata.send(constructAccelerationServoConfigMessage(90 - ctrlp.speed));
            firmata.send(constructTurningServoConfigMessage(90 + ctrlp.turning));
        }
        catch (SerialException e) {
            Log.e(LOGTAG, e.toString());
        }
    }

    private ServoConfigMessage constructAccelerationServoConfigMessage(int speed) {
        ServoConfigMessage servo = new ServoConfigMessage();
        servo.setPin(8);
        servo.setMinPulse(544);
        servo.setMaxPulse(2400);
        servo.setAngle(speed);
        return servo;
    }

    private ServoConfigMessage constructTurningServoConfigMessage(int turning) {
        ServoConfigMessage servo = new ServoConfigMessage();
        servo.setPin(9);
        servo.setMinPulse(544);
        servo.setMaxPulse(2400);
        servo.setAngle(turning);
        return servo;
    }
}
