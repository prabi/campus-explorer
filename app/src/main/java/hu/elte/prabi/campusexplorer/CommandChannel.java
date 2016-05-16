package hu.elte.prabi.campusexplorer;

import android.util.Log;

import im.delight.android.ddp.Meteor;
import im.delight.android.ddp.MeteorCallback;

import rx.Observable;
import rx.Subscriber;

import org.json.JSONException;
import org.json.JSONObject;

class CommandChannel implements Observable.OnSubscribe<CommandChannel.Command> {

    private final String LOGTAG = "CommandChannel";

    // A Waypoint is a place on the surface of the Earth with a unique ID, that should be visited.
    class Waypoint {
        public double lat, lng;
        public int id;
        public String documentId;
        public boolean visited;
        public Waypoint(double lat, double lng, int id, String documentId) {
            this.lat = lat;
            this.lng = lng;
            this.id = id;
            this.documentId = documentId;
            this.visited = false;
        }
    }

    enum CommandVerb {
        ADD,
        REMOVE,
        CHANGE
    }

    enum CommandDataType {
        STATE,
        WAYPOINT,
        DOCUMENTID,
        OTHER
    }

    static class Command {
        public CommandVerb verb;
        public CommandDataType dataType;
        public Object data;
        public Command(CommandVerb verb, CommandDataType dataType, Object data) {
            this.verb = verb;
            this.dataType = dataType;
            this.data = data;
        }
    }

    private Meteor meteorClient;

    public CommandChannel(Meteor meteor) {
        meteorClient = meteor;
    }

    private class CommandCallbacks implements MeteorCallback {

        final Subscriber<? super Command> observer;

        public CommandCallbacks(final Subscriber<? super Command> subscriber) {
            observer = subscriber;
        }

        @Override
        public void onConnect(boolean signedInAutomatically) {
            Log.i(LOGTAG, "Connected to DDP server.");
        }

        @Override
        public void onDisconnect() {
            Log.i(LOGTAG, "Disconnected from DDP server.");
            if (!observer.isUnsubscribed()) {
                observer.onCompleted();
            }
        }

        @Override
        public void onException(Exception e) {
            if (!observer.isUnsubscribed()) {
                observer.onError(e);
            }
        }

        @Override
        public void onDataAdded(String collectionName, String documentID, String newValuesJson) {
            if (!observer.isUnsubscribed()) {
                CommandVerb verb = CommandVerb.ADD;
                CommandDataType dataType;
                Object data;
                try {
                    switch (collectionName) {
                        case "directionwaypoints": {
                            dataType = CommandDataType.WAYPOINT;
                            JSONObject jObject = new JSONObject(newValuesJson);
                            data = new Waypoint(jObject.getDouble("lat"),
                                    jObject.getDouble("lng"),
                                    jObject.getInt("id"),
                                    documentID);
                            observer.onNext(new Command(verb, dataType, data));
                            break;
                        }
                        case "robotstate": {
                            dataType = CommandDataType.STATE;
                            JSONObject jObject = new JSONObject(newValuesJson);
                            data = jObject.getString("state");
                            observer.onNext(new Command(verb, dataType, data));
                            break;
                        }
                        default:
                            Log.d(LOGTAG, "DDP data - " + collectionName + " - was added.");
                            break;
                    }
                }
                catch (JSONException e) {
                    Log.e(LOGTAG, e.toString());
                }
            }
        }

        @Override
        public void onDataChanged(String collectionName, String documentID, String updatedValuesJson, String removedValuesJson) {
            Log.w(LOGTAG, "DDP data changed without any handling.");
        }

        @Override
        public void onDataRemoved(String collectionName, String documentID) {
            if (!observer.isUnsubscribed()) {
                if (!collectionName.equals("directionwaypoints")) {
                    Log.e(LOGTAG, "DDP data - other than a waypoint - was deleted.");
                    return;
                }
                observer.onNext(new Command(CommandVerb.REMOVE, CommandDataType.DOCUMENTID, documentID));
            }
        }
    }

    @Override
    public void call(Subscriber<? super Command> subscriber) {
        if (meteorClient.isConnected()) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onError(new Exception("Command channel has already been opened."));
            }
            return;
        }
        meteorClient.addCallback(new CommandCallbacks(subscriber));
        meteorClient.connect();
    }
}
