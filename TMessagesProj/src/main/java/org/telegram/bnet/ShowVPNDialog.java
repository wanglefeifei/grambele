package org.telegram.bnet;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import org.telegram.bnet.aidl.BnetAidlInterface;
import org.telegram.bnet.bean.BnetServiceJoinParams;

import java.util.UUID;

public class ShowVPNDialog extends Activity {
    private static final int VPN_REQUEST_CODE = 0x0F;
    private BnetServiceJoinParams bnetServiceJoinParams;

  private BnetAidlInterface bnetAidlInterface;
    private Intent mIntentConnectorService;

    private static ServiceConnection serviceConnection;
    private boolean serviceBind = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startVPN();
    }
    public void startVPN() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);//wait user confirmation, will call onActivityResult
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            StartVpvJoin();
        }
    }

    public void StartVpvJoin() {
        String dWalletAddr = SharePreferenceMain.getSharedPreference(getApplicationContext()).getdWalletAddr();
        if (dWalletAddr == null) {
            dWalletAddr = UUID.randomUUID().toString();
            SharePreferenceMain.getSharedPreference(getApplicationContext()).savedWalletAddr(dWalletAddr);
        }
        BnetServiceJoin(null, dWalletAddr, "", 32);
    }

    public void BnetServiceJoin(String nWalletAddr, String dWalletAddr, String deviceAddr, int maskBit) {
        bnetServiceJoinParams = new BnetServiceJoinParams(nWalletAddr, dWalletAddr, deviceAddr, maskBit);
        if (bnetAidlInterface == null || !serviceBind) {
            startAndBindService();
        } else {
            StartBnetServiceJoin();
        }
    }


    private void startAndBindService() {
        Intent bnetService = new Intent(this, BnetService.class);
        startService(bnetService);


        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                bnetAidlInterface = BnetAidlInterface.Stub.asInterface(iBinder);
                serviceBind = true;
                StartBnetServiceJoin();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {

            }
        };
        mIntentConnectorService = new Intent(getApplicationContext(), BnetService.class);
        bindService(mIntentConnectorService, serviceConnection, BIND_AUTO_CREATE);
    }

    private void StartBnetServiceJoin() {
        if (bnetServiceJoinParams != null && bnetAidlInterface != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        bnetAidlInterface.join(bnetServiceJoinParams.getnWalletAddr(), bnetServiceJoinParams.getdWalletAddr(), bnetServiceJoinParams.getDeviceAddr(), bnetServiceJoinParams.getMaskBit());
                   finish();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            try {
                bnetAidlInterface.CStartService();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
