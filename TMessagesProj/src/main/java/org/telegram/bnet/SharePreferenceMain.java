package org.telegram.bnet;


import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;


public class SharePreferenceMain {
    private static SharePreferenceMain spm;
    public static SharedPreferences sp;
    private Editor edit;
    // public data
    private final static String BASE_MODE_DATA = "BASE_MODE_DATA";

    //number of starts
    private final String StartCount = "StartCount";

    //User login information
    private final String UserLoginInfo = "UserLoginInfo";



    private final String drawPostionInfo="drawPostionInfo";


    private SharePreferenceMain() {
    }

    @SuppressWarnings("static-access")
    public synchronized static SharePreferenceMain getSharedPreference(
            Context ct) {
        sp = ct.getSharedPreferences(BASE_MODE_DATA, ct.MODE_PRIVATE);
        if (spm == null) {

            return spm = new SharePreferenceMain();
        }

        return spm;
    }


    /*
         * number of starts
         */
    public int getStartCount() {
        int rtns = 0;
        if (sp.contains(StartCount)) {
            rtns = sp.getInt(StartCount, 0);
        }
        return rtns;
    }

    public boolean saveStartCount() {

        edit = sp.edit();
        int rtns = sp.getInt(StartCount, 0);
        edit.putInt(StartCount, rtns + 1);
        edit.commit();
        return true;
    }

    /*
            * save
            */
    public String getdrawPostionInfo(String exName) {
        String rtns = null;
        if (sp.contains(drawPostionInfo+exName)) {
            rtns = sp.getString(drawPostionInfo+exName,null);
        }
        return rtns;
    }

    public boolean savedrawPostionInfo(String exName, String info) {

        edit = sp.edit();
        edit.putString(drawPostionInfo+exName,info);
        edit.commit();
        return true;
    }



    /*
            * save
            */
    public String getdWalletAddr() {
        String rtns = null;
        if (sp.contains("getdWalletAddr")) {
            rtns = sp.getString("getdWalletAddr",null);
        }
        return rtns;
    }

    public boolean savedWalletAddr(String info) {

        edit = sp.edit();
        edit.putString("getdWalletAddr",info);
        edit.commit();
        return true;
    }

}
