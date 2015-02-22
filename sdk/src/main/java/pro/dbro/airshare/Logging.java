package pro.dbro.airshare;

import timber.log.Timber;

/**
 * Created by davidbrodsky on 2/21/15.
 */
public class Logging {

    static {
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
    }
}
