package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class GeocoderSettings {

    public static final int GEOCODER_DISABLED = 0;
    public static final int GEOCODER_SERVER_OPENSTREETMAP = 1;
    // DiamaneOS: there is no GrapheneOS geocoding server. GrapheneOS's setup wizard turns the
    // geocoder on with this constant, so it means OpenStreetMap here. GrapheneOS's stored value
    // (2) is not valid and reads back as the default, off.
    public static final int GEOCODER_SERVER_GRAPHENEOS = GEOCODER_SERVER_OPENSTREETMAP;

    public static final IntSetting GEOCODER_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GEOCODER,
            GEOCODER_DISABLED, // default
            GEOCODER_SERVER_OPENSTREETMAP, GEOCODER_DISABLED // valid values
    );
}
