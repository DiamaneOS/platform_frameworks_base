package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class GeocoderSettings {

    public static final int GEOCODER_DISABLED = 0;
    public static final int GEOCODER_SERVER_OPENSTREETMAP = 1;
    // DiamaneOS: not offered (DiamaneOS uses no GrapheneOS geocoding server). Kept because
    // GrapheneOS's NetworkLocation app still names it; a stored 2 reads back as the default, off.
    public static final int GEOCODER_SERVER_GRAPHENEOS = 2;

    public static final IntSetting GEOCODER_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GEOCODER,
            GEOCODER_DISABLED, // default
            GEOCODER_SERVER_OPENSTREETMAP, GEOCODER_DISABLED // valid values
    );
}
