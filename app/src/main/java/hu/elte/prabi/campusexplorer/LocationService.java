package hu.elte.prabi.campusexplorer;

import android.app.Activity;
import android.content.IntentSender;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

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

import rx.Observable;
import rx.Subscriber;

class LocationService implements Observable.OnSubscribe<Location> {

    private final String LOGTAG = "LocationService";

    private Activity activity;
    private LocationRequest locationRequest;
    private GoogleApiClient gApiClient;
    private LocationCallbacks locationCallbacks;

    public LocationService(Activity currentActivity, int intervalMS, int fastestIntervalMS) {
        activity = currentActivity;
        locationRequest = new LocationRequest();
        locationRequest.setInterval(intervalMS);
        locationRequest.setFastestInterval(fastestIntervalMS);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    private class LocationCallbacks
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            LocationListener {

        final Subscriber<? super Location> observer;

        public LocationCallbacks(final Subscriber<? super Location> subscriber) {
            observer = subscriber;
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            // Make sure phone settings are appropriate for navigation.
            LocationSettingsRequest locSetReq = new LocationSettingsRequest.Builder()
                    .addLocationRequest(locationRequest).build();
            PendingResult<LocationSettingsResult> result =
                    LocationServices.SettingsApi.checkLocationSettings(gApiClient, locSetReq);
            result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
                @Override
                public void onResult(@NonNull LocationSettingsResult result) {
                    final Status status = result.getStatus();
                    if (status.getStatusCode() == LocationSettingsStatusCodes.RESOLUTION_REQUIRED) {
                        try {
                            status.startResolutionForResult(activity, 0x1);
                        } catch (IntentSender.SendIntentException e) {
                            Log.e(LOGTAG, e.toString());
                        }
                    }
                }
            });

            //noinspection MissingPermission
            LocationServices.FusedLocationApi.requestLocationUpdates(gApiClient, locationRequest, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.w(LOGTAG, "Location service has suspended.");
            if (!observer.isUnsubscribed()) {
                observer.onNext(null);
            }
        }

        @Override
        public void onLocationChanged(Location location) {
            if (!observer.isUnsubscribed()) {
                observer.onNext(location);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (!observer.isUnsubscribed()) {
                observer.onError(new Exception("Connection to location service failed."));
            }
        }
    }

    @Override
    public void call(final Subscriber<? super Location> subscriber) {

        // If Google API Client has already been initialized, abort.
        if (gApiClient != null) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onError(new Exception("Google API Client has already been initialized."));
            }
            return;
        }

        // Set up and start Google API Client.
        locationCallbacks = new LocationCallbacks(subscriber);
        gApiClient = new GoogleApiClient.Builder(activity)
                .addConnectionCallbacks(locationCallbacks)
                .addOnConnectionFailedListener(locationCallbacks)
                .addApi(LocationServices.API)
                .build();
        gApiClient.connect();
    }

    public void terminate() {
        LocationServices.FusedLocationApi.removeLocationUpdates(gApiClient, locationCallbacks);
        gApiClient.disconnect();
    }
}
