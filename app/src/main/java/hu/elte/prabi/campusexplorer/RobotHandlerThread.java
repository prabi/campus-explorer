package hu.elte.prabi.campusexplorer;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.Nullable;
import android.util.Log;

import im.delight.android.ddp.Meteor;
import im.delight.android.ddp.MeteorCallback;

import java.util.LinkedList;
import java.util.List;

import name.antonsmirnov.firmata.Firmata;
import name.antonsmirnov.firmata.IFirmata;
import name.antonsmirnov.firmata.InitListener;
import name.antonsmirnov.firmata.message.ServoConfigMessage;
import name.antonsmirnov.firmata.message.SetPinModeMessage;
import name.antonsmirnov.firmata.serial.SerialException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * RobotHandlerThread pulls all input and controls the robot in a loop.
 */
class RobotHandlerThread extends HandlerThread implements MeteorCallback {

    private final String LOGTAG = "RobotHandlerThread";

    private Handler robotHandler;
    private Handler uiHandler;
    private Firmata robot;
    private boolean terminated = false;
    private Location lastLocation;

    public synchronized void registerLocation(Location newLocation) {
        lastLocation = newLocation;
    }

    private synchronized Location getLastLocation() {
        return lastLocation;
    }

    // A Waypoint is a place on the surface of the Earth with a unique ID, that should be visited.
    private class Waypoint {
        public double lat, lng;
        public int id;
        public String documentId;
        public boolean visited;
        public Waypoint(double lat, double lng, int id, String documentId){
            this.lat = lat;
            this.lng = lng;
            this.id = id;
            this.documentId = documentId;
            this.visited = false;
        }
    }

    // Communication channel with the user, and places to visit.
    private Meteor mMeteor;
    private List<Waypoint> waypoints = new LinkedList<>();

    public RobotHandlerThread(Handler handler,   // Message Handler of the UI thread
                              Context context,   // Application Context for Meteor DDP
                              UsbDevice device,
                              UsbDeviceConnection connection) {
        super("RobotHandlerThread");
        uiHandler = handler;
        robot = new Firmata(new FelhrUSBSerialAdapter(device, connection));
        mMeteor = new Meteor(context, context.getResources().getString(R.string.ddp_uri));
    }

    @Override
    protected void onLooperPrepared() {
        robotHandler = new Handler(getLooper());

        // Robot initialization.
        robot.addListener(new InitListener(new InitListener.Listener() {
            public void onInitialized() {
                Message notification = uiHandler.obtainMessage(0, "Initialized Firmata.");
                notification.sendToTarget();
                try {
                    // Set pin 8 and 9 to servo mode.
                    robot.send(new SetPinModeMessage(8, SetPinModeMessage.PIN_MODE.SERVO.getMode()));
                    robot.send(new SetPinModeMessage(9, SetPinModeMessage.PIN_MODE.SERVO.getMode()));
                }
                catch (SerialException e) {
                    Log.e(LOGTAG, e.toString());
                }
                // Set the robot's speed to 0 and turn its wheels to look straight forward.
                stopRobot();
                // Start control loop.
                tickForControl();
            }
        }));

        // Log unhandled bytes received from USB Serial.
        robot.addListener(new IFirmata.StubListener() {
            @Override
            public void onUnknownByteReceived(int byteValue) {
                Log.d(LOGTAG, "Received unexpected byte: " + (char)byteValue);
            }
        });

        // Start USB Serial communication.
        try {
            robot.getSerial().start();
        }
        catch (SerialException e) {
            Log.e(LOGTAG, e.toString());
        }

        // Set up communication channel with the user.
        mMeteor.addCallback(this);
        mMeteor.connect();
    }

    public void terminate() {
        terminated = true;
        quitSafely();
        try {
            robot.getSerial().stop();
        }
        catch (SerialException e) {
            Log.e(LOGTAG, e.toString());
        }
        mMeteor.disconnect();
        mMeteor.removeCallback(this);
    }

    private void tickForControl() {
        robotHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (terminated) {
                    // The control loop should terminate.
                    return;
                }

                // If there is no waypoint to reach, just rest.
                Waypoint goal = getNextUnvisitedWaypoint();
                if (goal == null) {
                    stopRobot(); tickForControl(); return;
                }

                // If location data is insufficient, wait for better GPS signal.
                Location location = getLastLocation();
                if (location == null || !location.hasAccuracy()) {
                    stopRobot(); tickForControl(); return;
                }
                float accuracy = location.getAccuracy();
                if (accuracy > 10.0f) {
                    stopRobot(); tickForControl(); return;
                }

                // Determine the next waypoint to reach.
                float[] dist = new float[]{0.0f, 0.0f};  // distance in metres and initial bearing
                Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                                         goal.lat, goal.lng, dist);
                while (dist[0] < accuracy) {
                    goal.visited = true;
                    Message notification =
                            uiHandler.obtainMessage(0, "Reached waypoint " + goal.documentId);
                    notification.sendToTarget();
                    goal = getNextUnvisitedWaypoint();
                    if (goal == null) break;
                    Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                            goal.lat, goal.lng, dist);
                }

                // Every waypoint was visited.
                if (goal == null) {
                    stopRobot(); tickForControl(); return;
                }

                // Go toward the first unvisited waypoint.
                float turning = 0.0f;
                if (location.hasBearing()) {
                    float bearing = location.getBearing();
                    if (bearing > dist[1]) {
                        bearing = bearing - 360.0f;
                    }
                    turning = dist[1] - bearing;
                    if (turning > 180.0) {
                        turning = turning - 360.0f;
                    }
                }
                steerRobot(60, 90 + Math.min(Math.max(Math.round(turning), -30), 30));

                // Set up next iteration of control loop.
                tickForControl();
            }
        }, 250);  // 4 control events per second
    }

    @Nullable
    private Waypoint getNextUnvisitedWaypoint() {
        for (Waypoint wp : waypoints) {
            if (!wp.visited) {
                return wp;
            }
        }
        return null;
    }

    private void stopRobot() {
        steerRobot(90, 90);
    }

    private void steerRobot(int speed, int turning) {
        try {
            robot.send(constructAccelerationServoConfigMessage(speed));
            robot.send(constructTurningServoConfigMessage(turning));
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

    @Override
    public void onConnect(boolean signedInAutomatically) {
        Message notification = uiHandler.obtainMessage(0, "Connected to server.");
        notification.sendToTarget();
    }

    @Override
    public void onDisconnect() {
        Message notification = uiHandler.obtainMessage(0, "Disconnected from server.");
        notification.sendToTarget();
    }

    @Override
    public void onDataAdded(String collectionName,
                            String documentID,
                            String newValuesJson) {
        try {
            if (!collectionName.equals("markers")) return;
            JSONObject jObject = new JSONObject(newValuesJson);
            waypoints.add(jObject.getInt("id"),
                          new Waypoint(jObject.getDouble("lat"),
                                       jObject.getDouble("lng"),
                                       jObject.getInt("id"),
                                       documentID));
            Message notification = uiHandler.obtainMessage(0, "Received new waypoint.");
            notification.sendToTarget();
        } catch (JSONException e) {
            Log.e(LOGTAG, e.toString());
        }
    }

    @Override
    public void onDataChanged(String collectionName,
                              String documentID,
                              String updatedValuesJson,
                              String removedValuesJson) {
        try {
            JSONObject jObject = new JSONObject(updatedValuesJson);
            if (!collectionName.equals("markers")) return;
            for (Waypoint wp : waypoints) {
                if (wp.documentId.equals(documentID)) {
                    if(jObject.has("lat"))
                        wp.lat = jObject.getDouble("lat");
                    if(jObject.has("lng"))
                        wp.lng = jObject.getDouble("lng");
                    if(jObject.has("id"))
                        wp.id = jObject.getInt("id");
                    break;
                }
            }
            Message notification = uiHandler.obtainMessage(0, "Received modified waypoint.");
            notification.sendToTarget();
        } catch (JSONException e) {
            Log.e(LOGTAG, e.toString());
        }
    }

    @Override
    public void onDataRemoved(String collectionName, String documentID) {
        if (!collectionName.equals("markers")) return;
        Waypoint toRemove = null;
        for (Waypoint wp : waypoints) {
            if (wp.documentId.equals(documentID)) {
                toRemove = wp;
                break;
            }
        }
        assert toRemove != null;
        waypoints.remove(toRemove);
        Message notification = uiHandler.obtainMessage(0, "Received deleted waypoint.");
        notification.sendToTarget();
    }

    @Override
    public void onException(Exception e) {
        Log.e(LOGTAG, e.toString());
    }
}
