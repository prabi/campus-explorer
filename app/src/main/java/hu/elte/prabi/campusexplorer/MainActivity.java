package hu.elte.prabi.campusexplorer;

import android.Manifest;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import im.delight.android.ddp.Meteor;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.Func3;
import rx.observables.ConnectableObservable;
import rx.schedulers.TimeInterval;

public class MainActivity extends AppCompatActivity {

    private final String LOGTAG = "MainActivity";

    Meteor meteor;
    LocationService locationService;
    UsbConnectionHandler usbHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ensure fine location permission is granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0);
        }

        // Define user command source.
        meteor = new Meteor(this, getString(R.string.ddp_uri));
        ConnectableObservable<CommandChannel.Command> commandSource = Observable
                .create(new CommandChannel(meteor))
                .publish();

        // Define location data source.
        locationService = new LocationService(this, 1000, 250);
        ConnectableObservable<Location> locationSource = Observable
                .create(locationService)
                .publish();

        // Create commands from location changes.
        Observable<CommandChannel.Command> locationCommands = locationSource
                .filter(new Func1<Location, Boolean>() {
                    @Override
                    public Boolean call(Location location) {
                        return location != null &&
                               location.hasAccuracy() && location.getAccuracy() <= 10.0f;
                    }
                })
                .map(new Func1<Location, CommandChannel.Command>() {
                    @Override
                    public CommandChannel.Command call(Location location) {
                        return new CommandChannel.Command(CommandChannel.CommandVerb.CHANGE,
                                                          CommandChannel.CommandDataType.OTHER,
                                                          location);
                    }
                });

        // Select the next unvisited waypoint to reach.
        Observable<CommandChannel.Waypoint> goalSource = commandSource
                // Select applicable commands from stream.
                .filter(new Func1<CommandChannel.Command, Boolean>() {
                    @Override
                    public Boolean call(CommandChannel.Command command) {
                        return command.dataType.equals(CommandChannel.CommandDataType.WAYPOINT) ||
                               command.dataType.equals(CommandChannel.CommandDataType.DOCUMENTID);
                // Add location commands as they appear.
            }}).mergeWith(locationCommands)
                // Maintain a list of waypoints to visit.
                .scan(new LinkedList<CommandChannel.Waypoint>(),
                     new Func2<List<CommandChannel.Waypoint>,
                                    CommandChannel.Command,
                                    List<CommandChannel.Waypoint>>() {
                    @Override
                    public List<CommandChannel.Waypoint> call(List<CommandChannel.Waypoint> waypoints,
                                                              CommandChannel.Command command) {
                        if (command.verb.equals(CommandChannel.CommandVerb.ADD)) {
                            CommandChannel.Waypoint wp = (CommandChannel.Waypoint) command.data;
                            waypoints.add(wp.id, wp);
                            Log.d(LOGTAG, "Added waypoint " + wp.documentId);
                        }
                        else if (command.verb.equals(CommandChannel.CommandVerb.REMOVE)) {
                            String documentID = (String) command.data;
                            CommandChannel.Waypoint toRemove = null;
                            for (CommandChannel.Waypoint wp : waypoints) {
                                if (wp.documentId.equals(documentID)) {
                                    toRemove = wp;
                                    break;
                                }
                            }
                            assert toRemove != null;
                            Log.d(LOGTAG, "Removed waypoint " + toRemove.documentId);
                            waypoints.remove(toRemove);
                        }
                        else {
                            Location loc = (Location) command.data;
                            float accuracy = loc.getAccuracy();
                            float[] dist = new float[]{0.0f};
                            for (CommandChannel.Waypoint wp : waypoints) {
                                if (wp.visited) continue;
                                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                                        wp.lat, wp.lng, dist);
                                if (dist[0] < accuracy) {
                                    wp.visited = true;
                                    Log.i(LOGTAG, "Sucessfully visited " + wp.documentId);
                                }
                            }
                        }
                        return waypoints;
                // Select the next unvisited waypoint.
            }}).map(new Func1<List<CommandChannel.Waypoint>, CommandChannel.Waypoint>() {
                    @Override
                    public CommandChannel.Waypoint call(List<CommandChannel.Waypoint> waypoints) {
                        for (CommandChannel.Waypoint wp : waypoints) {
                            if (!wp.visited) {
                                return wp;
                            }
                        }
                        return null;
            }}).distinct().doOnNext(new Action1<CommandChannel.Waypoint>() {
                    @Override
                    public void call(CommandChannel.Waypoint waypoint) {
                        if (waypoint != null) {
                            Log.i(LOGTAG, "New goal to reach: " + waypoint.documentId);
                        }
            }});

        // Indicate whether the robot is paused.
        Observable<Boolean> pausedSource = commandSource
                .filter(new Func1<CommandChannel.Command, Boolean>() {
                    @Override
                    public Boolean call(CommandChannel.Command command) {
                        return command.dataType.equals(CommandChannel.CommandDataType.STATE);
                }}).map(new Func1<CommandChannel.Command, Boolean>() {
                    @Override
                    public Boolean call(CommandChannel.Command command) {
                        return "Stop".equals(command.data);
                }});

        // Compute control parameters of the robot based on the inputs above.
        ConnectableObservable<Robot.ControlParams> robotControl = Observable
                .combineLatest(locationSource, goalSource, pausedSource,
                new Func3<Location, CommandChannel.Waypoint, Boolean, Robot.ControlParams>() {
                    @Override
                    public Robot.ControlParams call(Location location,
                                                    CommandChannel.Waypoint waypoint,
                                                    Boolean isPaused) {
                        // If the robot is paused, stop immediately.
                        if (isPaused) {
                            Log.d(LOGTAG, "Stopped, because the robot is paused.");
                            return new Robot.ControlParams(0, 0);
                        }

                        // If there is no waypoint to reach, just rest.
                        if (waypoint == null) {
                            Log.d(LOGTAG, "Stopped, because there's no unvisited waypoints to reach.");
                            return new Robot.ControlParams(0, 0);
                        }

                        // If location data is insufficient, wait for better GPS signal.
                        if (location == null || !location.hasAccuracy()) {
                            Log.d(LOGTAG, "Stopped, because no location with accuracy is present.");
                            return new Robot.ControlParams(0, 0);
                        }
                        float accuracy = location.getAccuracy();
                        if (accuracy > 10.0f) {
                            Log.d(LOGTAG, "Stopped, because of insufficient location accuracy.");
                            return new Robot.ControlParams(0, 0);
                        }

                        // Go toward the waypoint.
                        float[] dist = new float[]{0.0f, 0.0f};  // distance and initial bearing
                        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                                waypoint.lat, waypoint.lng, dist);
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
                        Log.d(LOGTAG, "Chasing waypoint with turning value " + Float.toString(turning));
                        return new Robot.ControlParams(30, Math.min(Math.max(Math.round(turning), -30), 30));
                }}).publish();

        // Set up USB connection management.
        usbHandler = new UsbConnectionHandler(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbConnectionHandler.USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbHandler, filter);

        // Send control commands to the robot.
        robotControl.subscribe(new Subscriber<Robot.ControlParams>() {
            @Override
            public void onCompleted() {
                Log.i(LOGTAG, "Robot Control subscriber received onCompleted message.");
            }

            @Override
            public void onError(Throwable e) {
                Log.e(LOGTAG, e.toString());
            }

            @Override
            public void onNext(Robot.ControlParams controlParams) {
                Robot robot = usbHandler.getRobot();
                if (robot != null) {
                    robot.steerRobot(controlParams);
                }
            }
        });

        // Stop robot if 2 seconds elapse without a robot control message.
        Observable.combineLatest(robotControl.timeInterval(),
                Observable.interval(1500, TimeUnit.MILLISECONDS),
                new Func2<TimeInterval<Robot.ControlParams>, Long, Long>() {
                    @Override
                    public Long call(TimeInterval<Robot.ControlParams> interval, Long ms) {
                        return interval.getIntervalInMilliseconds();
                    }
                }).filter(new Func1<Long, Boolean>() {
                    @Override
                    public Boolean call(Long intervalMS) {
                        return intervalMS > 2000;
                }}).subscribe(new Action1<Long>() {
                    @Override
                    public void call(Long intervalMS) {
                        Log.d(LOGTAG, "Security timer stopped robot.");
                        Robot robot = usbHandler.getRobot();
                        if (robot != null) {
                            robot.steerRobot(new Robot.ControlParams(0, 0));
                        }
                }});

        // Send location updates to user via DDP.
        locationSource.subscribe(new Action1<Location>() {
            @Override
            public void call(Location location) {
                if (meteor.isConnected()) {
                    meteor.call("LogPosition",
                            new Object[]{location.getLatitude(), location.getLongitude()});
                }
            }
        });

        // Start main data sources.
        robotControl.connect();
        commandSource.connect();
        locationSource.connect();
    }

    @Override
    public void onDestroy() {
        Robot robot = usbHandler.getRobot();
        if (robot != null) {
            robot.terminate();
        }
        if (meteor.isConnected()) {
            meteor.disconnect();
        }
        meteor.removeCallbacks();
        locationService.terminate();
        unregisterReceiver(usbHandler);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Log.e(LOGTAG, "User denied access to location service, app won't work.");
        }
    }
}
