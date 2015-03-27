package pro.dbro.airshare.sample;

import android.content.Context;


/**
 * Created by davidbrodsky on 9/21/14.
 */
public class PrefsManager {

    /** SharedPreferences store names */
    private static final String APP_PREFS = "prefs";

    /** SharedPreferences keys */
    private static final String APP_USERNAME = "name";

    public static boolean needsUsername(Context context) {
        return getUsername(context) == null;
    }

    public static String getUsername(Context context) {
        return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                      .getString(APP_USERNAME, null);
    }

    public static void setUsername(Context context, String username) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).edit()
               .putString(APP_USERNAME, username)
               .commit();
    }

    public static void clearState(Context context) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
