package org.telegram.bnet;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.telegram.bnet.aidl.BnetAidlInterface;
import org.telegram.bnet.bean.Join;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;


public class BnetService  extends Service {
    private static final String TAG = BnetService.class.getSimpleName();
    //private static  String VPN_ADDRESS = "10.208.0.1"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    // private static final String VPN_ROUTE = "10.0.0.0"; // Intercept everything
    public static final String BROADCAST_VPN_STATE = "network.b.VPN_STATE";
    private static boolean isRunning = false;
    private ParcelFileDescriptor vpnInterface = null;
    private PendingIntent pendingIntent;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;
    private Selector udpSelector;
    private Selector tcpSelector;


    IBnetBinder iBnetBinder;
    private static T bNetT;

    private synchronized static T getTInstance() {
        if (bNetT == null) {
            bNetT = new T();
        }
        return bNetT;
    }

    public BnetService() {

    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        if (iBnetBinder == null) {
            iBnetBinder = new IBnetBinder();
        }
        return iBnetBinder;
        //        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        executorService.shutdownNow();
        cleanup();
        super.onDestroy();
    }

    private void startVpn() {
        //protect m_udpSocket not be block by tun
        //        protect(Global.m_udpSocket);
        //start VPN
        isRunning = true;
        setupVPN();
        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<Packet>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<Packet>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<ByteBuffer>();
            //            executorService = Executors.newFixedThreadPool(5);
            //            executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
            //            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
            //            executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector));
            //            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this));
            //build vpn
            Global.vpnFileDescriptor = vpnInterface.getFileDescriptor();
            executorService.submit(new VPNRunnable(Global.vpnFileDescriptor,
                    deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            Log.i(TAG, "Started");
            //protect(t.m_udpSocket);
        } catch (IOException e) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            cleanup();
        }
    }

    private void cleanup() {
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();
        private FileDescriptor vpnFileDescriptor;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            Log.i(TAG, "Started");
            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();//vpnInterface.getFileDescriptor()
            Global.vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;
                while (!Thread.interrupted()) {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();
                    //  Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        if (Global.hnodereceivewhereis == false) {
                            //RECIEVE TUN MSG THEN SEND HeferPeer_WHERE_IS_PEER_REQ TO HNODE
                            byte[] whereIsMsg = new byte[142];
                            String heferId = "hefer_r9test";//length:64
                            byte[] strHeferId = heferId.getBytes();//walletid
                            System.arraycopy(strHeferId, 0, whereIsMsg, 0, strHeferId.length); //walletid
                            int tNodeid = Global.u2RNodeId;//u2
                            byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
                            System.arraycopy(u2RNodeId, 0, whereIsMsg, 64, u2RNodeId.length);
                            byte[] strToHeferId = new byte[64];
                            System.arraycopy(strToHeferId, 0, whereIsMsg, 66, strToHeferId.length);
                            int u2ToRNodeId = 0;
                            byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
                            System.arraycopy(b_u2ToRNodeId, 0, whereIsMsg, 130, b_u2ToRNodeId.length); // RNode_u2HNodeId
                            whereIsMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
                            whereIsMsg[133] = 41;//u1Type = HeferPeer_WHERE_IS_PEER_REQ
                            int u2Seq = 0;//u2
                            byte[] b_u2Seq = ByteConvert.ushortToBytes(u2Seq);
                            System.arraycopy(b_u2Seq, 0, whereIsMsg, 134, b_u2Seq.length); //u2Seq
                            long u4ReqAddr = 0;
                            byte[] b_u4ReqAddr = ByteConvert.uintToBytes(u4ReqAddr);
                            System.arraycopy(b_u4ReqAddr, 0, whereIsMsg, 136, b_u4ReqAddr.length);
                            int u2DefaultRNodeId = Global.u2DefaultRNodeId;
                            byte[] b_u2DefaultRNodeId = ByteConvert.ushortToBytes(u2DefaultRNodeId);
                            System.arraycopy(b_u2DefaultRNodeId, 0, whereIsMsg, 140, b_u2DefaultRNodeId.length); //u2Seq
                            //dst ip:172.217.26.36
                            InetAddress HNode = InetAddress.getByName("162.62.18.231");
                            bNetT.sendUdpMessage(whereIsMsg, HNode, 15555);
                            System.out.println("send whereis to hnode!");
                            Global.hadSendWhereIs = true;
                        }
                        if (HeferMsg_PeerRouteInd.u1Result == 1)//nowrussian
                        {
                            //data encrypt then send rnode34
                            AESCrypt aesobject;
                            //System.out.println("############ start encrypt data");
                            //SecretKeySpec key =  AESCrypt.generateKey("123456");
                            // byte[] iv = {0x01, 0x12, 0x23, 0x34, 0x45, 0x56, 0x67, 0x78,(byte)0x89,(byte) 0x7a, 0x6b, 0x5c, 0x4d, 0x3e, 0x2f, 0x10 };
                            int remaining = bufferToNetwork.remaining();
                            //System.out.println("############ bufferToNetwork.remaining:"+ remaining);
                            byte[] message = new byte[remaining + 6 + 136];
                            bufferToNetwork.get(message, 6 + 136, remaining);//buffer��ȡ��bufferToNetwork.remaining()����
                            //1,2byte
                            int longOfmessage = remaining + 2;
                            //System.out.println("############   longOfmessage:"+ longOfmessage);
                            byte[] b_u2longOfmessage = ByteConvert.ushortToBytes(longOfmessage);
                            System.arraycopy(b_u2longOfmessage, 0, message, 136, b_u2longOfmessage.length); //137�ĳ�136
                            //5,6byte
                            longOfmessage = remaining;
                            byte[] b_u2longOfmessage56 = ByteConvert.ushortToBytes(longOfmessage);
                            System.arraycopy(b_u2longOfmessage56, 0, message, 140, b_u2longOfmessage56.length); //141�ĳ�140
                            Global.hefer_header[133] = 51;//HeferPeer_DATA_IND;
                            //to rnodeid
                            System.arraycopy(HeferMsg_PeerRouteInd.RouteIndCodeStream, 0, Global.hefer_header, 130, 2);
                            //to nextpop heferid
                            System.arraycopy(HeferMsg_PeerRouteInd.RouteIndCodeStream, 10, Global.hefer_header, 66, 64);
                            //lpq;
                            System.arraycopy(Global.hefer_header, 0, message, 0, 136); //u2Seq
                            byte[] message_payload = new byte[remaining + 6];
                            System.arraycopy(message, 136, message_payload, 0, remaining + 6);
                            byte[] message_payload_enc = AesEncryptUtil.aesCbcNoPaddingEncrypt(message_payload, Global.aesKey, Global.aesIv);
                            byte[] message_send = new byte[136 + message_payload_enc.length];
                            System.arraycopy(Global.hefer_header, 0, message_send, 0, 136); //u2Seq
                            System.arraycopy(message_payload_enc, 0, message_send, 136, message_payload_enc.length);
                            InetAddress RNode33 = InetAddress.getByName("139.162.41.158");
                            bNetT.sendUdpMessage(message_send, RNode33, 56789);
                            /*
                               for (int i = 0;i<20;i++)
							   {
								   System.out.println("############### message"+(142+i)+":"+message[142+i]);
							   }
							   byte[] b_u2longOfmessage_3  = new byte[2];
							   System.arraycopy( message, 136, b_u2longOfmessage_3, 0,2); //u2Seq
							   int messagelong_3 = ByteConvert.bytesToUshort(b_u2longOfmessage_3);
							   System.out.println(" messagelong_3:"+messagelong_3);
							*/
                        }
                    } else//readbyte<0
                    {
                        dataSent = false;
                    }
                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining())
                            Global.vpnOutput.write(bufferFromNetwork);
                        dataReceived = true;
                        ByteBufferPool.release(bufferFromNetwork);
                    } else {
                        dataReceived = false;
                    }
                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataReceived)
                        Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "Stopping");
            } catch (IOException e) {
                Log.w(TAG, e.toString(), e);
            } finally {
                closeResources(vpnInput, Global.vpnOutput);
            }
        }
    }

    class IBnetBinder extends BnetAidlInterface.Stub {

        @Override
        public int create(String nWalletAddr, String masterAddr, int maskBit) throws RemoteException {
            //            InetAddress inetAddress = null;
            //            try {
            //                inetAddress = InetAddress.getByName(masterAddr);
            //            } catch (UnknownHostException e) {
            //                e.printStackTrace();
            //            }
            //            return getTInstance().create(nWalletAddr, inetAddress, maskBit);
            return 0;
        }


        @Override
        public int join(String nWalletAddr, String dWalletAddr, String deviceAddr, int maskBit) throws RemoteException {
            //            byte ip[] = new byte[]{0, 0, 0, 0};
            //            InetAddress expectAddress = null;
            //            try {
            //                expectAddress = InetAddress.getByAddress(ip);
            //            } catch (UnknownHostException e) {
            //                // TODO Auto-generated catch block
            //                e.printStackTrace();
            //            }
            //            nWalletAddr = "172M8JQj7hh1Uf1sYvTf8NtT9vwxJTbRXg";
            //            //            dWalletAddr = "172M8JQj7hh1Uf1sYvTf8NtT9vwxJT1234";
            //            maskBit = 32;
            //            Global.phoneNum = dWalletAddr;
            //            return getTInstance().join(nWalletAddr, dWalletAddr, expectAddress, maskBit);
            Join.nWalletAddr = nWalletAddr;
            Join.dWalletAddr = dWalletAddr;
            Join.deviceAddr = deviceAddr;
            Join.maskBit = maskBit;
            Intent intent = new Intent(getApplicationContext(), LocalVPNService.class);
            Log.e(TAG + "test", "11111111111111111");
            startService(intent);
            return 0;
        }

        @Override
        public int accept(String deviceAddr, int maskBit) throws RemoteException {
            InetAddress inetAddressDevice = null;
            try {
                inetAddressDevice = InetAddress.getByName(deviceAddr);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            return getTInstance().accept(inetAddressDevice, maskBit);
        }

        @Override
        public int reject() throws RemoteException {
            return getTInstance().reject();
        }

        @Override
        public int leave() throws RemoteException {
            //            int rtn = getTInstance().leave();
            //            if (rtn == 0) {
            //                //                BnetService.this.stopSelf();
            //                Process.killProcess(Process.myPid());
            //            }
            Log.e("showbnetservicelog",""+ Process.myPid());
            Process.killProcess(Process.myPid());
            Log.e("showbnetservicelog","kill:"+Process.myPid());
            return 0;
        }

        @Override
        public String getRequest() throws RemoteException {
            return getTInstance().getRequest();
        }

        @Override
        public int getStatus() throws RemoteException {
            return getTInstance().getStatus();
        }

        @Override
        public void sendUdpMessage(byte[] data, String to, int port) throws RemoteException {
            InetAddress inetAddressTo = null;
            try {
                inetAddressTo = InetAddress.getByName(to);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            getTInstance().sendUdpMessage(data, inetAddressTo, port);
        }

        @Override
        public void onUdpMessage(byte[] data) throws RemoteException {
            getTInstance().onUdpMessage(data);
        }

        @Override
        public void sendTunMessage(byte[] data) throws RemoteException {
            getTInstance().sendTunMessage(data);
        }

        @Override
        public void onTunMessage(byte[] data) throws RemoteException {
            getTInstance().onTunMessage(data);
        }

        @Override
        public void CStartService() throws RemoteException {
            //            new Thread(new Runnable() {
            //                @Override
            //                public void run() {
            //                    try {
            //                        Thread.sleep(5 * 1000);
            //                    } catch (InterruptedException e1) {
            //                        // TODO Auto-generated catch block
            //                        e1.printStackTrace();
            //                    }
            //                    Global.VPN_ADDRESS = Global.strLanIp;
            //                    startVpn();
            //                }
            //            }).start();
        }
    }


    private void setupVPN() {
        if (vpnInterface == null) {
            //            Builder builder = new Builder();
            //            System.out.println(" Global.VPN_ADDRESS:" + Global.VPN_ADDRESS);
            //            builder.addAddress(Global.VPN_ADDRESS, 32);
            //            builder.addRoute(VPN_ROUTE, 0);
            //            builder.setMtu(1300);
            //            builder.addDnsServer("8.8.8.8");//need read from config msg
            //            vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(pendingIntent).establish();
        }
        // protect(1);
    }

}