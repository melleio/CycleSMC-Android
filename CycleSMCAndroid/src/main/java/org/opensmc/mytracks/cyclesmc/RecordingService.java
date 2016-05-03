/**     Cycle Philly, Copyright 2014 Code for Philly
 *
 *   @author Lloyd Emelle <lloyd@codeforamerica.org>
 *   @author Christopher Le Dantec <ledantec@gatech.edu>
 *   @author Anhong Guo <guoanhong15@gmail.com>
 *
 *   Updated/Modified for Philly's app deployment. Based on the
 *   CycleTracks codebase for SFCTA and Cycle Atlanta.
 *
 *   CycleTracks, Copyright 2009,2010 San Francisco County Transportation Authority
 *                                    San Francisco, CA, USA
 *
 * 	 @author Billy Charlton <billy.charlton@sfcta.org>
 *
 *   This file is part of CycleTracks.
 *
 *   CycleTracks is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   CycleTracks is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with CycleTracks.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.opensmc.mytracks.cyclesmc;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;

import java.util.Timer;
import java.util.TimerTask;

import org.opensmc.mytracks.cyclesmc.R;

public class RecordingService extends Service implements LocationListener {
    RecordingActivity recordActivity;
    LocationManager lm = null;
    DbAdapter mDb;

    // Bike bell variables
    static int BELL_FIRST_INTERVAL = 20;
    static int BELL_NEXT_INTERVAL = 5;
    Timer timer;
    SoundPool soundpool;
    int bikebell;
    final Handler mHandler = new Handler();
    final Runnable mRemindUser = new Runnable() {
        public void run() {
            remindUser();
        }
    };

    // Aspects of the currently recording trip
    double latestUpdate;
    Location lastLocation;
    float distanceTraveled;
    float curSpeed, maxSpeed;
    TripData trip;

    public final static int STATE_IDLE = 0;
    public final static int STATE_RECORDING = 1;
    public final static int STATE_PAUSED = 2;
    public final static int STATE_FULL = 3;

    int state = STATE_IDLE;
    private final MyServiceBinder myServiceBinder = new MyServiceBinder();

    // ---SERVICE methods - required! -----------------
    @Override
    public IBinder onBind(Intent arg0) {
        return myServiceBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        soundpool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        bikebell = soundpool.load(this.getBaseContext(), R.raw.bikebell, 1);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
    }

    public class MyServiceBinder extends Binder implements IRecordService {
        public int getState() {
            return state;
        }

        public void startRecording(TripData trip) {
            RecordingService.this.startRecording(trip);
        }

        public void cancelRecording() {
            RecordingService.this.cancelRecording();
        }

        public void pauseRecording() {
            RecordingService.this.pauseRecording();
        }

        public void resumeRecording() {
            RecordingService.this.resumeRecording();
        }

        public long finishRecording() {
            return RecordingService.this.finishRecording();
        }

        public long getCurrentTrip() {
            if (RecordingService.this.trip != null) {
                return RecordingService.this.trip.tripid;
            }
            return -1;
        }

        public void reset() {
            RecordingService.this.state = STATE_IDLE;
        }

        public void setListener(RecordingActivity ra) {
            RecordingService.this.recordActivity = ra;
            notifyListeners();
        }
    }

    // ---end SERVICE methods -------------------------

    public void startRecording(TripData trip) {
        this.state = STATE_RECORDING;
        this.trip = trip;

        curSpeed = maxSpeed = distanceTraveled = 0.0f;
        lastLocation = null;

        // Add the notify bar and blinking light
        setNotification();

        // Start listening for GPS updates!
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);

        // Set up timer for bike bell
        if (timer != null) {
            timer.cancel();
            timer.purge();
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(mRemindUser);
            }
        }, BELL_FIRST_INTERVAL * 60000, BELL_NEXT_INTERVAL * 60000);
    }

    public void pauseRecording() {
        this.state = STATE_PAUSED;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        lm.removeUpdates(this);
    }

    public void resumeRecording() {
        this.state = STATE_RECORDING;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
    }

    public long finishRecording() {
        this.state = STATE_FULL;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return 1;
        }
        lm.removeUpdates(this);

        clearNotifications();

        return trip.tripid;
    }

    public void cancelRecording() {
        if (trip != null) {
            trip.dropTrip();
        }

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        lm.removeUpdates(this);

		clearNotifications();
        this.state = STATE_IDLE;
	}

	public void registerUpdates(RecordingActivity r) {
		this.recordActivity = r;
	}

	public TripData getCurrentTrip() {
		return trip;
	}

	// LocationListener implementation:
	@Override
	public void onLocationChanged(Location loc) {
		if (loc != null) {
			// Only save one beep per second.
			double currentTime = System.currentTimeMillis();
			if (currentTime - latestUpdate > 999) {

				latestUpdate = currentTime;
				updateTripStats(loc);
				boolean rtn = trip.addPointNow(loc, currentTime, distanceTraveled);
				if (!rtn) {
	                //Log.e("FAIL", "Couldn't write to DB");
				}

				// Update the status page every time, if we can.
				notifyListeners();
			}
		}
	}

	@Override
	public void onProviderDisabled(String arg0) {
	}

	@Override
	public void onProviderEnabled(String arg0) {
	}

	@Override
	public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
	}
	// END LocationListener implementation:

	public void remindUser() {
	    // soundpool.play(bikebell, 1.0f, 1.0f, 1, 0, 1.0f);
	    // CA - commented out this function because I don't think it is being used correctly, the loop is set to 0 but the sound keeps repeating, likely because remindUser() function keeps getting called.
	    //noting parameters - soundpool.play(soundID, leftVolume, rightVolume, priority, loop, rate)

		int icon = R.drawable.icon25;
        long when = System.currentTimeMillis();
        int minutes = (int) (when - trip.startTime) / 60000;
        CharSequence contentText = "Tap to see your ongoing trip";

		NotificationCompat.Builder mBuilder =
				new NotificationCompat.Builder(this)
						.setSmallIcon(icon)
						.setContentTitle(getString(R.string.recordingData))
						.setContentText(contentText);
		// Creates an explicit intent for an Activity in your app


		Context context = this;
		Intent notificationIntent = new Intent(context, RecordingActivity.class);
		PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

		// The stack builder object will contain an artificial back stack for the
// started Activity.
// This ensures that navigating backward from the Activity leads out of
// your application to the Home screen.
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
// Adds the back stack for the Intent (but not the Intent itself)
		stackBuilder.addParentStack(RecordingActivity.class);
// Adds the Intent that starts the Activity to the top of the stack
		stackBuilder.addNextIntent(notificationIntent);
		PendingIntent resultPendingIntent =
				stackBuilder.getPendingIntent(
						0,
						PendingIntent.FLAG_UPDATE_CURRENT
				);
		mBuilder.setContentIntent(resultPendingIntent);
		NotificationManager mNotificationManager =
				(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        final int RECORDING_ID = 1;
		mNotificationManager.notify(RECORDING_ID, mBuilder.build());
	}

	private void setNotification() {
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		int icon = R.drawable.icon25;
		CharSequence tickerText = "Recording...";
		long when = System.currentTimeMillis();

        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(icon)
                        .setContentTitle(getString(R.string.recordingData))
                        .setContentText(tickerText);
        // Creates an explicit intent for an Activity in your app


        Context context = this;
        Intent notificationIntent = new Intent(context, RecordingActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

        // The stack builder object will contain an artificial back stack for the
// started Activity.
// This ensures that navigating backward from the Activity leads out of
// your application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
// Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(RecordingActivity.class);
// Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(notificationIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        final int RECORDING_ID = 1;
        mNotificationManager.notify(RECORDING_ID, mBuilder.build());
		CharSequence contentTitle = getString(R.string.recordingData);
		CharSequence contentText = "Tap to see your ongoing trip";
        mBuilder.setContentIntent(resultPendingIntent);
        mNotificationManager.notify(RECORDING_ID, mBuilder.build());
	}

	private void clearNotifications() {
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.cancelAll();

		if (timer!=null) {
            timer.cancel();
            timer.purge();
		}
	}

    private void updateTripStats(Location newLocation) {
        final float spdConvert = 2.2369f;

    	// Stats should only be updated if accuracy is decent
    	if (newLocation.getAccuracy()< 20) {
            // Speed data is sometimes awful, too:
            curSpeed = newLocation.getSpeed() * spdConvert;
            if (curSpeed < 60.0f) {
            	maxSpeed = Math.max(maxSpeed, curSpeed);
            }
            if (lastLocation != null) {
                float segmentDistance = lastLocation.distanceTo(newLocation);
                distanceTraveled = distanceTraveled + segmentDistance;
            }

            lastLocation = newLocation;
    	}
    }

    void notifyListeners() {
    	if (recordActivity != null) {
    		recordActivity.updateStatus(trip.numpoints, distanceTraveled, curSpeed, maxSpeed);
    	}
    }
}
