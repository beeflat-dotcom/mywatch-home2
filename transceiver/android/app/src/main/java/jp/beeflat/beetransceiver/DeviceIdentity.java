package jp.beeflat.beetransceiver;



import android.content.Context;

import android.content.SharedPreferences;

import java.util.UUID;



final class DeviceIdentity {

    private static final String PREFS = "bee_transceiver";

    private static final String KEY = "device_uuid";

    static UUID get(Context context) {

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String raw = p.getString(KEY, "");

        if (raw != null && !raw.isEmpty()) {

            try { return UUID.fromString(raw); } catch (Exception ignored) {}

        }

        UUID value = UUID.randomUUID();

        p.edit().putString(KEY, value.toString()).apply();

        return value;

    }

    private DeviceIdentity() {}

}