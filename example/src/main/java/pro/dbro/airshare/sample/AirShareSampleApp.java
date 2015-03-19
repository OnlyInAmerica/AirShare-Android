package pro.dbro.airshare.sample;

import android.app.Application;

import pro.dbro.airshare.Logging;
import timber.log.Timber;

/**
 * Created by davidbrodsky on 3/17/15.
 */
public class AirShareSampleApp extends Application {

    public static final String AIR_SHARE_SERVICE_NAME = "AirShareExample";

    @Override public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new CrashReportingTree());
        }

        // If we abandon Timber logging in this app, enable below line
        // to enable Timber logging in sdk 
        //Logging.forceLogging();
    }

    /** A tree which logs important information for crash reporting. */
    private static class CrashReportingTree extends Timber.HollowTree {
        @Override public void i(String message, Object... args) {
            // TODO e.g., Crashlytics.log(String.format(message, args));
        }

        @Override public void i(Throwable t, String message, Object... args) {
            i(message, args); // Just add to the log.
        }

        @Override public void e(String message, Object... args) {
            i("ERROR: " + message, args); // Just add to the log.
        }

        @Override public void e(Throwable t, String message, Object... args) {
            e(message, args);

            // TODO e.g., Crashlytics.logException(t);
        }
    }
}