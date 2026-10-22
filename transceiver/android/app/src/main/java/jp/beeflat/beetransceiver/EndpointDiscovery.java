package jp.beeflat.beetransceiver;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EndpointDiscovery {
    static String resolve(Context context) {
        SharedPreferences p=context.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        String cached=p.getString(UploadConfig.PREF_CACHED_ENDPOINT,"");
        long cachedAt=p.getLong(UploadConfig.PREF_CACHED_ENDPOINT_AT,0L);
        if(valid(cached)&&now-cachedAt<UploadConfig.ENDPOINT_CACHE_MS)return trim(cached);
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(UploadConfig.DISCOVERY_URL).openConnection();
            c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setUseCaches(false);
            int code=c.getResponseCode();
            if(code<200||code>=300)return valid(cached)?trim(cached):"";
            String text=read(c.getInputStream());
            JSONObject o=new JSONObject(text);
            String endpoint=o.optString("external_api_url","");
            if(!"running".equalsIgnoreCase(o.optString("phase",""))||!valid(endpoint))
                return valid(cached)?trim(cached):"";
            endpoint=trim(endpoint);
            p.edit().putString(UploadConfig.PREF_CACHED_ENDPOINT,endpoint)
                    .putLong(UploadConfig.PREF_CACHED_ENDPOINT_AT,now).apply();
            return endpoint;
        }catch(Exception e){
            return valid(cached)?trim(cached):"";
        }finally{if(c!=null)c.disconnect();}
    }
    static void invalidate(Context c) {c.getSharedPreferences(UploadConfig.PREFS,Context.MODE_PRIVATE).edit().remove(UploadConfig.PREF_CACHED_ENDPOINT_AT).apply();}
    private static String read(InputStream in)throws Exception{
        try(InputStream x=in){byte[]b=new byte[4096];StringBuilder s=new StringBuilder();int n;
            while((n=x.read(b))>=0)if(n>0)s.append(new String(b,0,n,StandardCharsets.UTF_8));return s.toString();}
    }
    private static boolean valid(String s){return s!=null&&s.trim().startsWith("https://")&&s.trim().length()>12;}
    private static String trim(String s){s=s==null?"":s.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}
    private EndpointDiscovery(){}
}
