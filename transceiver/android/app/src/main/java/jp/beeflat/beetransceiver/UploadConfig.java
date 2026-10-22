package jp.beeflat.beetransceiver;

final class UploadConfig {
    static final String PREFS = "bee_transceiver";
    static final String PREF_SERVER_URL = "audio_server_url";
    static final String PREF_MOBILE_UPLOAD = "mobile_upload";
    static final String API_TOKEN = BuildConfig.BEE_AUDIO_TOKEN;
    static String token(android.content.Context c) { String v=c.getSharedPreferences(PREFS,android.content.Context.MODE_PRIVATE).getString("ptt_device_token","");return v.isEmpty()?API_TOKEN:v; }
    static void saveToken(android.content.Context c,String token) {c.getSharedPreferences(PREFS,android.content.Context.MODE_PRIVATE).edit().putString("ptt_device_token",token).apply();}
    static final String DISCOVERY_URL = BuildConfig.BEE_ENDPOINT_DISCOVERY_URL;
    static final String PREF_CACHED_ENDPOINT = "cached_external_endpoint";
    static final String PREF_CACHED_ENDPOINT_AT = "cached_external_endpoint_at";
    static final long ENDPOINT_CACHE_MS = 10 * 60 * 1000L;
    static String serverUrl(android.content.Context c){ return ""; }
    static boolean mobileUpload(android.content.Context c){ return true; }
    private UploadConfig() {}
}
