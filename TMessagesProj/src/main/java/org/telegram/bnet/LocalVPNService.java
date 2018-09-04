package org.telegram.bnet;

/*
Reference:
	https://www.jb51.net/article/118050.htm

Low-Level API:
	VPN data I/O:
		FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
		FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

	WAN data I/O:
		protect(my_UDPSocket);

Process:
	Start()
		Get configuration via https://d.vin/t.php?w=xxxxxx
		Register H-Node via UDP

	Run()
		Forward UDP to tun
		Forword tun to UDP
		Send heartbeat via UDP
*/

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.apache.http.util.EncodingUtils;
import org.telegram.messenger.R;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


class Global{
	public static FileDescriptor vpnFileDescriptor;
	public static DatagramSocket m_udpSocket;    //socket
	public static int            	u2HNodeHbSeq;
	public static boolean        	hnodereceivewhereis = false;
	public static byte           	u1Tried = 10;
	public static byte[]         	matchedToHeferId = new byte[64];
	public static byte[]         	matched_b_u2ToRNodeId =  new byte[2];
	public static  boolean 	   	defaultNodeMatched = false;
	public static InetAddress PeerNode = null;
	public static byte[]         	b_u2ToRNodePort =  new byte[2];
	public static 	int 		toRNodePeerPort;
	public static  boolean       	HNodeRcvMachDone = false;
	public static boolean        	RcvDefaultNodeTryOrKeepLive = false;
	public static boolean           hadStartRun = false;
	public static  boolean       	hadSendWhereIs = false;
	public static  boolean 		hadRcvRegAck = false;
	public static  byte[]        	hefer_header = new byte[136];
	public static String aesIv = "any";
	public static String aesKey = "any";
	public static FileChannel vpnOutput;
	// RNode_Config;
	public static 	int       	u2RNodeId;
	public static 	int         u2DefaultRNodeId; //= 34;
	public static String strHNodeIp;
	public static  	int      	u2HNodePort;
	public static 	int       	u2HNodePort2;
	public static String strHeferId;
	public static String strSubnetScope;       //exp:10.0.1.0/24
	public static String strLanIp; //= "10.208.0.1";
	public static String strLanNetMask;

	public static String strGateway;
	public static String dns;
	public static String privateKey;
	public static String m_walletid;//address
	public  static String VPN_ADDRESS ;
	//public static 	String 		phoneNum = "17801113110";//need activity config
	public static String phoneNum = "18511665456";//need activity config
	//public static 	String 		heferid = "hefer_r9test";//russian
	public static String heferid = "172M8JQj7hh1Uf1sYvTf8NtT9vwxJTbRXg";//need activity config
	public static String configPara;
	public static String idAndKey;
}

class  HeferMsg_PeerRouteInd{
	public static byte            	u1Result = 0;//=0 send hnode,=1 send rnode33
	public static byte[] 		   	RouteIndCodeStream = new byte[77];//结构上下码流
	public static int    			u2DestRNodeId;
	public static long   			u4DestNet;
	public static long   			u4DestNetMask;
	public static byte[] 			strNextHopHeferId = new byte[64];
	public static int    			u2NextHopRNodeId;
	public static byte   			u1Metric;
}

//ip addr transfer to int
class IPUtil {
	public static long ipToLong(String strIp) {
		String[]ip = strIp.split("\\.");
		return (Long.parseLong(ip[0]) << 24) + (Long.parseLong(ip[1]) << 16) + (Long.parseLong(ip[2]) << 8) + Long.parseLong(ip[3]);
	}
	public static String longToIP(long longIp) {
		StringBuffer sb = new StringBuffer("");
		sb.append(String.valueOf((longIp >>> 24)));
		sb.append(".");
		sb.append(String.valueOf((longIp & 0x00FFFFFF) >>> 16));
		sb.append(".");
		sb.append(String.valueOf((longIp & 0x0000FFFF) >>> 8));
		sb.append(".");
		sb.append(String.valueOf((longIp & 0x000000FF)));
		return sb.toString();
	}
}

//byte transfer
class ByteConvert {
	public static byte[] longToBytes(long n) {
		byte[] b = new byte[8];
		b[7] = (byte) (n & 0xff);
		b[6] = (byte) (n >> 8  & 0xff);
		b[5] = (byte) (n >> 16 & 0xff);
		b[4] = (byte) (n >> 24 & 0xff);
		b[3] = (byte) (n >> 32 & 0xff);
		b[2] = (byte) (n >> 40 & 0xff);
		b[1] = (byte) (n >> 48 & 0xff);
		b[0] = (byte) (n >> 56 & 0xff);
		return b;
	}
	public static void longToBytes( long n, byte[] array, int offset ){
		array[7+offset] = (byte) (n & 0xff);
		array[6+offset] = (byte) (n >> 8 & 0xff);
		array[5+offset] = (byte) (n >> 16 & 0xff);
		array[4+offset] = (byte) (n >> 24 & 0xff);
		array[3+offset] = (byte) (n >> 32 & 0xff);
		array[2+offset] = (byte) (n >> 40 & 0xff);
		array[1+offset] = (byte) (n >> 48 & 0xff);
		array[0+offset] = (byte) (n >> 56 & 0xff);
	}
	public static long bytesToLong( byte[] array )
	{
		return ((((long) array[ 0] & 0xff) << 56)
				| (((long) array[ 1] & 0xff) << 48)
				| (((long) array[ 2] & 0xff) << 40)
				| (((long) array[ 3] & 0xff) << 32)
				| (((long) array[ 4] & 0xff) << 24)
				| (((long) array[ 5] & 0xff) << 16)
				| (((long) array[ 6] & 0xff) << 8)
				| (((long) array[ 7] & 0xff) << 0));
	}
	public static long bytesToLong( byte[] array, int offset )
	{
		return ((((long) array[offset + 0] & 0xff) << 56)
				| (((long) array[offset + 1] & 0xff) << 48)
				| (((long) array[offset + 2] & 0xff) << 40)
				| (((long) array[offset + 3] & 0xff) << 32)
				| (((long) array[offset + 4] & 0xff) << 24)
				| (((long) array[offset + 5] & 0xff) << 16)
				| (((long) array[offset + 6] & 0xff) << 8)
				| (((long) array[offset + 7] & 0xff) << 0));
	}
	public static byte[] intToBytes(int n) {
		byte[] b = new byte[4];
		b[3] = (byte) (n & 0xff);
		b[2] = (byte) (n >> 8 & 0xff);
		b[1] = (byte) (n >> 16 & 0xff);
		b[0] = (byte) (n >> 24 & 0xff);
		return b;
	}
	public static void intToBytes( int n, byte[] array, int offset ){
		array[3+offset] = (byte) (n & 0xff);
		array[2+offset] = (byte) (n >> 8 & 0xff);
		array[1+offset] = (byte) (n >> 16 & 0xff);
		array[offset] = (byte) (n >> 24 & 0xff);
	}
	public static int bytesToInt(byte b[]) {
		return    b[3] & 0xff
				| (b[2] & 0xff) << 8
				| (b[1] & 0xff) << 16
				| (b[0] & 0xff) << 24;
	}
	public static int bytesToInt(byte b[], int offset) {
		return    b[offset+3] & 0xff
				| (b[offset+2] & 0xff) << 8
				| (b[offset+1] & 0xff) << 16
				| (b[offset] & 0xff) << 24;
	}
	public static byte[] uintToBytes( long n )
	{
		byte[] b = new byte[4];
		b[3] = (byte) (n & 0xff);
		b[2] = (byte) (n >> 8 & 0xff);
		b[1] = (byte) (n >> 16 & 0xff);
		b[0] = (byte) (n >> 24 & 0xff);

		return b;
	}
	public static void uintToBytes( long n, byte[] array, int offset ){
		array[3+offset] = (byte) (n );
		array[2+offset] = (byte) (n >> 8 & 0xff);
		array[1+offset] = (byte) (n >> 16 & 0xff);
		array[offset]   = (byte) (n >> 24 & 0xff);
	}
	public static long bytesToUint(byte[] array) {
		return ((long) (array[3] & 0xff))
				| ((long) (array[2] & 0xff)) << 8
				| ((long) (array[1] & 0xff)) << 16
				| ((long) (array[0] & 0xff)) << 24;
	}
	public static long bytesToUint(byte[] array, int offset) {
		return ((long) (array[offset+3] & 0xff))
				| ((long) (array[offset+2] & 0xff)) << 8
				| ((long) (array[offset+1] & 0xff)) << 16
				| ((long) (array[offset]   & 0xff)) << 24;
	}
	public static byte[] shortToBytes(short n) {
		byte[] b = new byte[2];
		b[1] = (byte) ( n       & 0xff);
		b[0] = (byte) ((n >> 8) & 0xff);
		return b;
	}
	public static void shortToBytes(short n, byte[] array, int offset ) {
		array[offset+1] = (byte) ( n       & 0xff);
		array[offset] = (byte) ((n >> 8) & 0xff);
	}
	public static short bytesToShort(byte[] b){
		return (short)( b[1] & 0xff
				|(b[0] & 0xff) << 8 );
	}
	public static short bytesToShort(byte[] b, int offset){
		return (short)( b[offset+1] & 0xff
				|(b[offset]    & 0xff) << 8 );
	}
	public static byte[] ushortToBytes(int n) {
		byte[] b = new byte[2];
		b[1] = (byte) ( n       & 0xff);
		b[0] = (byte) ((n >> 8) & 0xff);
		return b;
	}
	public static void ushortToBytes(int n, byte[] array, int offset ) {
		array[offset+1] = (byte) ( n       & 0xff);
		array[offset] = (byte)   ((n >> 8) & 0xff);
	}
	public static int bytesToUshort(byte b[]) {
		return    b[1] & 0xff
				| (b[0] & 0xff) << 8;
	}
	public static int bytesToUshort(byte b[], int offset) {
		return    b[offset+1] & 0xff
				| (b[offset]   & 0xff) << 8;
	}
	public static byte[] ubyteToBytes( int n ){
		byte[] b = new byte[1];
		b[0] = (byte) (n & 0xff);
		return b;
	}
	public static void ubyteToBytes( int n, byte[] array, int offset ){
		array[0] = (byte) (n & 0xff);
	}
	public static int bytesToUbyte( byte[] array ){
		return array[0] & 0xff;
	}
	public static int bytesToUbyte( byte[] array, int offset ){
		return array[offset] & 0xff;
	}
}

// This is callback API for UdpRecvThread
interface UdpSocketEvent
{
	void onUdpMessage(byte[] data);
}

// This is UDP receiving thread.
class UdpRecvThread
{
	boolean m_IsReceiving = false;   //Keep receiving status
	public void startRecv (final DatagramSocket sock, final UdpSocketEvent evt)
	{
		m_IsReceiving = true;
		new Thread(new Runnable()
		{
			public void run()
			{
				while (m_IsReceiving)
				{
					try
					{
						//byte[] inBuff = new byte[2000];  //The max size for packet is 2000
						byte[] inBuff = new byte[3000];
						DatagramPacket inPacket = new DatagramPacket(inBuff, inBuff.length);
						sock.receive (inPacket);  //block operation
						evt.onUdpMessage(inBuff);
					}
					catch (IOException e)
					{
						System.out.println("recv UDP socket failed");
					}
				}
				System.out.println("UdpRecvThread is stopped.");
			}
		}).start();
	}
	public void stopRecv()
	{
		m_IsReceiving = false;
	}
}

// This is callback API for TunRecvThread
interface TunSocketEvent
{
	void onTunMessage(byte[] data);
}

// This is Tun receiving thread.
class TunRecvThread extends VpnService
{
	/*
	boolean              m_IsReceiving = false;   //Keep receiving status
	//ParcelFileDescriptor m_vpnInterface = new ParcelFileDescriptor(null) ;
	ParcelFileDescriptor m_vpnInterface ;
	FileInputStream      m_in  = null;
	FileOutputStream     m_out = null;
	public void startRecv (String lanAddr, String dns, final TunSocketEvent evt) throws IOException
	{
		m_IsReceiving = true;
		//setup VpnService
		Builder builder = new Builder();
		builder.setMtu(1300);
		builder.addAddress(lanAddr, 32);
		builder.addDnsServer(dns);
		builder.addRoute("0.0.0.0", 0);
		m_vpnInterface = builder.establish();
		//get VpnService I/O API
		m_in  = new FileInputStream(m_vpnInterface.getFileDescriptor());
		m_out = new FileOutputStream(m_vpnInterface.getFileDescriptor());

		//start receiving
		new Thread (new Runnable()
		{
			public void run()
			{
				while (m_IsReceiving)
				{
					try
					{
						byte[] inBuff = new byte[2000];  //The max size for packet is 2000
						// Allocate the buffer for a single packet.
						int length = m_in.read(inBuff);
						evt.onTunMessage(inBuff);
					}
					catch (IOException e)
					{
			    			System.out.println("recv TUN socket failed");
					}
				}
				System.out.println("TunRecvThread is stopped.");
			}
		}).start();

	}
	public void stopRecv()
	{
		m_IsReceiving = false;
		//m_in.close();
		//m_out.close();
	}
	public void send(byte[] data)
	{
		if (m_out != null)
		{
			try
			{
				m_out.write(data);
			}
			catch (IOException e)
			{
				System.out.println("send TUN socket failed");
			}
		}
	}
	*/
}

// This is Main Thread.
class T implements Runnable, UdpSocketEvent, TunSocketEvent
{
	//Wallet
	private String m_nWalletAddr = "";  //Network wallet ID
	private String m_dWalletAddr = "";  //Device wallet ID
	// the value of m_status
	public static final int Inited = 0;
	public static final int Configed = 1;
	public static final int Connected = 2;
	public static final int Connectting = 3;
	//UDP
	private UdpRecvThread  m_udpRecvThread = new UdpRecvThread();  //thread
	//Tun
	private TunRecvThread  m_tunRecvSocket = new TunRecvThread();  //Tun socket
	//Status
	private int m_status = Inited;  //hefer status

	//====================================================================
	//Enum & Defines
	//------------------------------------------------------------
	//typedef enum
	//{
	//Chain managenment related
	public static final int HeferChain_JOIN_REQ  = 1;
	public static final int HeferChain_JOIN_CFM = 2;
	public static final int HeferChain_SYNC_NCT_REQ = 3;
	public static final int HeferChain_SYNC_NCT_CFM = 4;
	public static final int HeferChain_RELOCATE_REQ = 5;
	public static final int HeferChain_RELOCATE_CFM = 6;
	//Route related
	public static final int HeferChain_WHERE_IS_SERVING = 11;
	public static final int HeferChain_SERVING_IS_HERE = 12;
	//Peer register related
	public static final int HeferPeer_REGISTER_REQ      = 21;
	public static final int HeferPeer_REGISTER_ACK = 22;
	public static final int HeferPeer_REGISTER_NACK = 23;
	//Peer link related
	public static final int HeferPeer_WHERE_IS_PEER_REQ = 41;
	public static final int HeferPeer_MATCH_START = 42;
	public static final int HeferPeer_MATCH_TRY = 43;
	public static final int HeferPeer_MATCH_DONE = 44;
	public static final int HeferPeer_MATCH_ROUTE_IND = 45;
	public static final int HeferPeer_MATCH_DONE_CONFIRM = 46;
	public static final int HeferPeer_MATCH_LOST_IND    = 47;
	//Peer transfer related
	public static final int HeferPeer_DATA_IND      = 51;
	public static final int HeferPeer_KEEPALIVE_REQ = 52;
	public static final int HeferPeer_ALIVELOST_IND = 53;
	public static final int HeferPeer_INVALID_DEST_RNODE_IND = 54;
	//Peer payment related
	public static final int HeferPeer_PLEASE_PAY_ME = 61;
	public static final int HeferPeer_DO_PAY = 62;
	//} HeferMsg_MsgTypes;
	//Hefer's settings
	//Reason:
	// 1) MTU is always 1500, it make us can send 700 new bytes at most.
	// 2) NAT tunnel timer out is always 5 minutes (300 second), so we choose 20 seconds for safety.
	// 3) GSM TCH send 33+header bytes every 20 ms, so we guess it will < 500 bytes.
	//macro define
	public static final int     HeferPeer_ROUTELIFE   =  20000;//The heart beat for NAT tunnel is 20 seconds (20000 ms).
	public static final int     HeferPeer_ROUTELOST   =  5;
	public static final int     HeferPeer_MYLINKS   =  3;
	public static final int   	HeferPeer_FORWARDLINKS   =  10;
	public static final int   	HeferPeer_BUFFERLIFE   =  200;
	public static final int   	HeferPeer_BUFFERCOUNT   =  5;
	public static final int   	HeferPeer_BUFFERSIZE   =  1600;
	public static final int   	HeferId_SIZE   =  64;
	//====================================================================
	//Enum & Defines
	//------------------------------------------------------------
	//typedef enum
	//{
	public static final int 	HeferNat_STATE_UNKNOWN = 0;
	public static final int 	HeferNat_STATE_THROUGH = 1; //// DES PORT CHANGE.SRC PORT NOT CHANGE
	public static final int 	HeferNat_STATE_NO_THROUGH = 2;//// DES PORT CHANGE.SRC PORT ALSO CHANGE
	//} HeferNat_STATE_E;

	public static final int 	RNode_LOCALIP = 0;//192.168.0.x
	public static final int 	RNode_LOCALPORT = 56789;        //port
	public static final int  	RNode_PEERNODEMAX = 50;
	public static final int  	RNode_INTRANODEMAX	= RNode_PEERNODEMAX;
	public static final int  	RNode_INTERNODEMAX = (RNode_INTRANODEMAX*2);
	public static final int  	RNode_ValidStartIdx = 0;
	public static final int  	RNode_IntraHeferNode = 0;//rnode with the same heferId
	public static final int  	RNode_InterHeferNode = 1;//rnode with different heferId
	public static final int  	RNode_P2PTRY_MAX  = 10 ; //5   //we try 5 packet for every node
	public static final int  	RNode_ROUTELOST = 5;//If we lost 5 continuous heartbeat, we think the link is lost.
	public static final int  	FLOW_Kb_NUM = 1024;
	public static final int 	FLOW_Mb_NUM = 1024*1024;
	public static final int 	FLOW_Gb_NUM = 1024*1024*1024;
	public static final int 	RNODE_PAYFOR_FLOW = 10*FLOW_Mb_NUM;
	public static final double 	RNODE_PAY_AMOUNT = 1.0;
	public static final int 	RNodeTimerDuration_REGISTER = 3000; //try register a node every 3 sec
	public static final int  	RNodeTimerDuration_MATCH = 10000 ;//30000  //try register a node every 30 sec
	public static final int  	RNodeTimerDuration_TEST =  2000;  //try register a node every 5 sec
	public static final int  	RNodeTimerDuration_HEART = 10000; //5000   //HeferCloud_ROUTELIFE
	public static final int  	RNodeTimerDuration_FORWARDTEST = 5000;
	public static final int  	RNodeTimerDuration_MATCH_RESULT = 10000;
	public static final int 	RNodeTimerDuration_WAITING_MATCH_RSLT = 180000;
	public static final int  	PeerNode_NULL  = 0	;
	public static final int  	PeerNode_MATCHED = 1;
	public static final int  	PeerNode_LOSTALIVE = 2;
	public static final int  	PeerNode_TRYINGP2P = 3;
	public static final int 	PeerNode_P2PFAIL   = 4;
	public static final int  	HeferCloudNat_VISIBLE = 0;
	public static final int   	HeferCloudNat_INVISIBLE  = 1;
	public static final int   	HeferCloudNat_UNREACHABLE = 2;

	//RNode_gInfo
	private int RNode_gInfo_u2HNodeHbSeq = 0;//RNode_gInfo.u2HNodeHbSeq = 0;
	private boolean m_connectted = false;
	//parse geted walletid
	public void  parseWalletid(String resultStr)
	{
		String[] resultStrArray = resultStr.split("\\: ");
		/*
		for (int i = 0; i < resultStrArray.length; i++) {
			System.out.println( resultStrArray[i]);
		}
		*/
		Global.m_walletid = resultStrArray[2];
		//如果返回的idAndKey有变化，则此处需要修改
		Global.privateKey =resultStrArray[1].substring(0,resultStrArray[1].length()- 8);//去掉“回车+address”
	}
	//parse geted para
	public void  parsePara(String resultStr)
	{
		String[] resultStrArray = resultStr.split("\\|");
		/*
		for (int i = 0; i < resultStrArray.length; i++) {
			System.out.println( resultStrArray[i]);
		}
		*/
		Global.u2RNodeId = Integer.valueOf(resultStrArray[0]);
		Global.u2DefaultRNodeId = Integer.valueOf(resultStrArray[1]);
		Global.strHNodeIp = resultStrArray[2];
		Global.u2HNodePort = Integer.valueOf(resultStrArray[3]);
		Global.u2HNodePort2 = Integer.valueOf(resultStrArray[4]);
		Global.dns = resultStrArray[5];
		Global.strLanIp = resultStrArray[6];
		Global.strLanNetMask = resultStrArray[7];
	}
	public static byte[] ipToBytesByInet(String ipAddr) {
		try {
			return InetAddress.getByName(ipAddr).getAddress();
		} catch (Exception e) {
			throw new IllegalArgumentException(ipAddr + " is invalid IP");
		}
	}
	private void RNode_sendHeferHeartbeatToDefaultNode(InetAddress NodeAddr, int PeerPort)
	{
		byte []  heartMsg = new byte[137];
		String heferId = Global.heferid;//length:64
		byte[] strHeferId  = heferId.getBytes();//二维码	//walletid
		System.arraycopy(strHeferId, 0, heartMsg, 0,strHeferId.length); //walletid
		int tNodeid = Global.u2RNodeId ;//u2
		byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
		System.arraycopy(u2RNodeId, 0, heartMsg, 64,u2RNodeId.length);
		System.arraycopy(Global.matchedToHeferId, 0, heartMsg, 66,Global.matchedToHeferId.length);
		System.arraycopy(Global.matched_b_u2ToRNodeId, 0, heartMsg, 130,Global.matched_b_u2ToRNodeId.length); // RNode_u2HNodeId
		heartMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
		heartMsg[133] = HeferPeer_KEEPALIVE_REQ;//u1Type
		int u2Seq = 0;//u2
		byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
		System.arraycopy(b_u2Seq, 0, heartMsg, 134,b_u2Seq.length); //u2Seq
		//copy header msg to global
		System.arraycopy(heartMsg, 0, Global.hefer_header, 0,136); //u2Seq
		//defaut node ip port need read from Hnode's msg
		sendUdpMessage (heartMsg, NodeAddr, PeerPort);
		System.out.println("send  heartbeat to defaultnode "+Global.u2DefaultRNodeId + " PeerPort:"+PeerPort);
	}
	private void RNode_sendHeferHeartbeatToHNode(int heartbeatseq)
	{
		try
		{
			byte []  heartMsg = new byte[137];
			String heferId = Global.heferid;//length:64
			byte[] strHeferId  = heferId.getBytes();//walletid
			System.arraycopy(strHeferId, 0, heartMsg, 0,strHeferId.length); //walletid
			int tNodeid = Global.u2RNodeId;//u2
			byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
			System.arraycopy(u2RNodeId, 0, heartMsg, 64,u2RNodeId.length);
			byte[] strToHeferId = new byte[64];
			System.arraycopy(strToHeferId, 0, heartMsg, 66,strToHeferId.length);
			int u2ToRNodeId = 0;
			byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
			System.arraycopy(b_u2ToRNodeId, 0, heartMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
			heartMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
			heartMsg[133] = HeferPeer_KEEPALIVE_REQ;//u1Type
			int u2Seq = heartbeatseq;//u2
			byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
			System.arraycopy(b_u2Seq, 0, heartMsg, 134,b_u2Seq.length); //u2Seq
			//Hnode node ip port need read config from Hnode
			InetAddress HNode = InetAddress.getByName(Global.strHNodeIp);
			sendUdpMessage (heartMsg, HNode, Global.u2HNodePort);
			System.out.println("send  heartbeat to hnode! heartbeat seq:"+ heartbeatseq);
		}
		catch (IOException e)
		{
			System.out.println("send  heartbeat to hnode failed");
		}
	}
	//create a new network named as nWalletAddr, and the master's IP is masterAddr/maskBit.
	//return value: if succeed, return 0.
	public int create(String nWalletAddr, InetAddress masterAddr, int maskBit)
	{
		//create a new network
		return 0;
	}

	public void getParaFromNet()
	{	//private String getWalletid(String  phoneNum )
		// read walletid from H-Node
		Global.idAndKey = getWalletid(Global.phoneNum);
		System.out.println("Global.idAndKey:"+Global.idAndKey);
		while(Global.idAndKey == "")
		{
			Global.idAndKey = getWalletid(Global.phoneNum);
			try {
				Thread.sleep (1 * 1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		parseWalletid(Global.idAndKey);//idAndKey = private key + address(wallet id)
		System.out.println("Global.m_walletid:"+Global.m_walletid);
		System.out.println("Global.privateKey:"+Global.privateKey);
		// read configuratin from H-Node
		System.out.println("m_walletid:"+Global.m_walletid);
		Global.configPara = getConfiguration(Global.m_walletid);
		System.out.println("config:"+Global.configPara);
		while(Global.configPara == "")
		{
			Global.configPara = getConfiguration(Global.m_walletid);
			try {
				Thread.sleep (1 * 1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		parsePara(Global.configPara);
		System.out.println("###Global.u2RNodeId:"+Global.u2RNodeId);
		System.out.println("###Global.u2DefaultRNodeId:"+Global.u2DefaultRNodeId);
		System.out.println("###Global.strLanIp:"+Global.strLanIp);
		System.out.println("###Global.strHNodeIp:"+Global.strHNodeIp);
		//save to m_hnodeIP ...
		m_status = Configed;

	}

	public void RNode_sendRegToHNode()
	{
		Thread thread = new Thread(){
			@Override
			public void run(){
				super.run();
				while(Global.hadRcvRegAck == false)
				{
					try
					{
						// Register to H-node
						byte []  registerMsg = new byte[146+18];
						String heferId = Global.heferid;//length:64
						byte[] strHeferId  = heferId.getBytes();//二维码	//walletid
						System.arraycopy(strHeferId, 0, registerMsg, 0,strHeferId.length); //walletid
						int tNodeid = Global.u2RNodeId;//u2
						byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
						System.arraycopy(u2RNodeId, 0, registerMsg, 64,u2RNodeId.length);
						byte[] strToHeferId = new byte[64];
						System.arraycopy(strToHeferId, 0, registerMsg, 66,strToHeferId.length);
						int u2ToRNodeId = 0;
						byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
						System.arraycopy(b_u2ToRNodeId, 0, registerMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
						registerMsg[132] = 0; //Always be 0
						registerMsg[133] = HeferPeer_REGISTER_REQ;//u1Type
						int u2Seq = 0;//u2
						byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
						System.arraycopy(b_u2Seq, 0, registerMsg, 134,b_u2Seq.length); //u2Seq
						System.out.println("!!!!!!!Global.strLanIp:"+Global.strLanIp);
						long iplong = IPUtil.ipToLong(Global.strLanIp);
						long u4Subnet = iplong & 0xffffffff;
						//long u4Subnet = 0xad00001; //0xad00001<- meng  10.208.0.1
						byte[] b_u4Subnet =  ByteConvert.uintToBytes(u4Subnet);
						System.arraycopy(b_u4Subnet, 0, registerMsg, 136,b_u4Subnet.length);
						//long u4SubnetMask = 32;//engineer liu gived 0xffffffff<- meng
						long u4SubnetMask =  0xffffffff;
						byte[] b_u4SubnetMask = ByteConvert.uintToBytes(u4SubnetMask);
						System.arraycopy(b_u4SubnetMask, 0, registerMsg, 140,b_u4SubnetMask.length);
						registerMsg[144+18] = 1;
						registerMsg[145+18] =  0;//hnode handle tnode special
						InetAddress HNode = InetAddress.getByName(Global.strHNodeIp);//should get from config
						sendUdpMessage (registerMsg, HNode, Global.u2HNodePort);
						sendUdpMessage (registerMsg, HNode, Global.u2HNodePort2);
						m_status = Connectting;
						System.out.println("send register msg to hnode");
						//Thread.sleep (1 * 1000);
					}
					catch (IOException e)
					{
						System.out.println("send register msg failed");
					}

					try {
						Thread.sleep (3 * 1000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}//end while
			}
		};
		thread.start();

	}

	//join an existed network named as nWalletAddr, device wallet is dWalletAddr,
	//	and the expected's IP is deviceAddr/maskBit.
	//return value: if succeed, return 0.
	public int join(String nWalletAddr, String dWalletAddr, InetAddress deviceAddr, int maskBit)
	{
		//join an existed network
		try
		{
			// Start listening to H-node
			Global.m_udpSocket = new DatagramSocket(0);
			m_udpRecvThread.startRecv(Global.m_udpSocket, this);
			RNode_sendRegToHNode();

		}
		catch (IOException e)
		{
			System.out.println("create UDP socket failed");
			return 1;
		}
		return 0;
	}

/*
	//join an existed network named as nWalletAddr, device wallet is dWalletAddr,
	//	and the expected's IP is deviceAddr/maskBit.
	//return value: if succeed, return 0.
	public int join(String nWalletAddr, String dWalletAddr, InetAddress deviceAddr, int maskBit)
	{
		//join an existed network
		try
		{
			//private String getWalletid(String  phoneNum )
			// read walletid from H-Node
			Global.idAndKey = getWalletid(Global.phoneNum);
			System.out.println("Global.idAndKey:"+Global.idAndKey);
			while(Global.idAndKey == "")
			{
				Global.idAndKey = getWalletid(Global.phoneNum);
				try {
					Thread.sleep (1 * 1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			parseWalletid(Global.idAndKey);//idAndKey = private key + address(wallet id)
			System.out.println("Global.m_walletid:"+Global.m_walletid);
			System.out.println("Global.privateKey:"+Global.privateKey);
			// read configuratin from H-Node
			System.out.println("m_walletid:"+Global.m_walletid);
			Global.configPara = getConfiguration(Global.m_walletid);
			System.out.println("config:"+Global.configPara);
			while(Global.configPara == "")
			{
				Global.configPara = getConfiguration(Global.m_walletid);
				try {
					Thread.sleep (1 * 1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			parsePara(Global.configPara);
			System.out.println("###Global.u2RNodeId:"+Global.u2RNodeId);
			System.out.println("###Global.u2DefaultRNodeId:"+Global.u2DefaultRNodeId);
			System.out.println("###Global.strLanIp:"+Global.strLanIp);
			System.out.println("###Global.strHNodeIp:"+Global.strHNodeIp);
			//save to m_hnodeIP ...
			m_status = Configed;
			// Start listening to H-node
			Global.m_udpSocket = new DatagramSocket(0);
			m_udpRecvThread.startRecv(Global.m_udpSocket, this);
			// Register to H-node
			byte []  registerMsg = new byte[146+18];
			String heferId = "hefer_r9test";//length:64
			byte[] strHeferId  = heferId.getBytes();//二维码	//walletid
			System.arraycopy(strHeferId, 0, registerMsg, 0,strHeferId.length); //walletid
			int tNodeid = Global.u2RNodeId;//u2
			byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
			System.arraycopy(u2RNodeId, 0, registerMsg, 64,u2RNodeId.length);
			byte[] strToHeferId = new byte[64];
			System.arraycopy(strToHeferId, 0, registerMsg, 66,strToHeferId.length);
			int u2ToRNodeId = 0;
			byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
			System.arraycopy(b_u2ToRNodeId, 0, registerMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
			registerMsg[132] = 0; //Always be 0
			registerMsg[133] = HeferPeer_REGISTER_REQ;//u1Type
			int u2Seq = 0;//u2
			byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
			System.arraycopy(b_u2Seq, 0, registerMsg, 134,b_u2Seq.length); //u2Seq
			System.out.println("!!!!!!!Global.strLanIp:"+Global.strLanIp);
			long iplong = IPUtil.ipToLong(Global.strLanIp);
			long u4Subnet = iplong & 0xffffffff;
			//long u4Subnet = 0xad00001; //0xad00001<- meng  10.208.0.1
			byte[] b_u4Subnet =  ByteConvert.uintToBytes(u4Subnet);
			System.arraycopy(b_u4Subnet, 0, registerMsg, 136,b_u4Subnet.length);
			//long u4SubnetMask = 32;//engineer liu gived 0xffffffff<- meng
			long u4SubnetMask =  0xffffffff;
			byte[] b_u4SubnetMask = ByteConvert.uintToBytes(u4SubnetMask);
			System.arraycopy(b_u4SubnetMask, 0, registerMsg, 140,b_u4SubnetMask.length);
			registerMsg[144+18] = 1;
			registerMsg[145+18] =  0;//hnode handle tnode special
			InetAddress HNode =InetAddress.getByName(Global.strHNodeIp);//should get from config
			sendUdpMessage (registerMsg, HNode, 15555);
			sendUdpMessage (registerMsg, HNode, 17777);
			m_status = Connectting;
		}
		catch (IOException e)
		{
				System.out.println("create UDP socket failed");
				return 1;
		}
		return 0;
	}
*/

	//master accept a request, and give the device with deviceAddr/maskBit.
	//return value: if succeed, return 0.
	public int accept(InetAddress deviceAddr, int maskBit)
	{
		//master accept a request
		return 0;
	}
	//master reject the request
	//return value: if succeed, return 0.
	public int reject()
	{
		//master reject the request
		return 0;
	}
	//the device leave the network.
	//return value: if succeed, return 0.
	public int leave()
	{
		//the device leave the network.
		m_udpRecvThread.stopRecv();
		Global.m_udpSocket.close();
		//m_tunRecvSocket.stopRecv();
		m_connectted = false;
		return 0;
	}
	//master read the incoming request till no more request
	//return value: "dWalletAddr|deviceAddr|maskBit", empty means no more items.
	public String getRequest()
	{
		//master read the incoming request till no more request
		return "";
	}
	//get the status of the connection
	//return values: "Connecting", "Connected", "Left"
	public int getStatus()
	{
		//get the status of the connection
		return m_status;
	}
	//get walletid+private key from http://162.62.16.175/w.php?action=create&rand=sdfsdfs
	private String getWalletid(String phoneNum )//return walletid + private key
	{
		HttpURLConnection httpURLConnection = null;
		InputStream in = null;
		String config = "";
		//start my thread
		try
		{
			URL url = new URL("http://162.62.16.175/w.php?action=create&rand=" + phoneNum);
			httpURLConnection = (HttpURLConnection) url.openConnection();
			httpURLConnection.setConnectTimeout(5000);
			httpURLConnection.setReadTimeout(5000);
			int responsecode = httpURLConnection.getResponseCode();
			if(responsecode == 200)
			{
				in = httpURLConnection.getInputStream();
				byte[] bs = new byte[1024];
				int total = -1;
				while((total=in.read(bs)) != -1)
				{
					String part = new String(bs,0,total);
					config = config + part;
				}
			}
		}
		catch (MalformedURLException e)
		{
			System.out.println("URL format error");
		}
		catch (IOException e)
		{
			System.out.println("Connection error,get configuration failed");
		}
		finally
		{
			if(in != null)
			{
				try
				{
					in.close();
				}
				catch (IOException e)
				{
					System.out.println("inputStream for configuration closed");
				}
			}
			if(httpURLConnection != null)
			{
				httpURLConnection.disconnect();
			}
		}
		return config;  //private key and walletid(address)
	}

	//get config from http://d.vin/h/t.php?w=xxxxxx
	private String getConfiguration(String walletid)
	{
		HttpURLConnection httpURLConnection = null;
		InputStream in = null;
		String config = "";
		System.out.println("walletid:"+walletid);
		//start my thread
		try
		{
			URL url = new URL("http://bnet.services/h/t.php?w=" + walletid);
			httpURLConnection = (HttpURLConnection) url.openConnection();
			httpURLConnection.setConnectTimeout(5000);
			httpURLConnection.setReadTimeout(5000);
			int responsecode = httpURLConnection.getResponseCode();
			if(responsecode == 200)
			{
				in = httpURLConnection.getInputStream();
				byte[] bs = new byte[1024];
				int total = -1;
				while((total=in.read(bs)) != -1)
				{
					String part = new String(bs,0,total);
					config = config + part;
				}
			}
		}
		catch (MalformedURLException e)
		{
			System.out.println("URL format error");
		}
		catch (IOException e)
		{
			System.out.println("Connection error,get configuration failed");
		}
		finally
		{
			if(in != null)
			{
				try
				{
					in.close();
				}
				catch (IOException e)
				{
					System.out.println("inputStream for configuration closed");
				}
			}
			if(httpURLConnection != null)
			{
				httpURLConnection.disconnect();
			}
		}
		return config;

	}
	public void sendUdpMessage(byte[] data, InetAddress to, int port)
	{
		try
		{
			DatagramPacket packet = new DatagramPacket(data, data.length, to, port);
			Global.m_udpSocket.send(packet);
			//System.out.println("send a packet to UDP");
		}
		catch (IOException e)
		{
			System.out.println("send UDP message failed");
		}
	}
	public static String bytesToHexFun3(byte[] bytes) {
		StringBuilder buf = new StringBuilder(bytes.length * 2);
		for(byte b : bytes) {
			buf.append(String.format("%02x", new Integer(b & 0xff)));
		}

		return buf.toString();
	}

	public void onUdpMessage(byte[] data)
	{
		try {
			InputStream in_withcode;
			in_withcode = new ByteArrayInputStream(data);
			DataInputStream inputStream  =  new DataInputStream(in_withcode);
			int read=0;
			inputStream.skipBytes(133);
			read = inputStream.readUnsignedByte();
			//System.out.println("+++++++++msg type->data[133]: = "+read);
			//read = inputStream.readInt();//readInt() read int from stream
			//read = inputStream.readUnsignedShort();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if(data[133] == HeferPeer_REGISTER_ACK )//22
		{
			System.out.println("got REGISTER_ACK from HNODE,data[136]:"+data[136]);//register success
			//System.out.println("got REGISTER_ACK from HNODE,data[137]:"+data[137]);	// DES PORT CHANGE.SRC PORT NOT CHANGE
			if(data[136] == 0)
			{
				System.out.println("REGISTER_ACK: RNode_REG_SUCCESS");
				Global.hadRcvRegAck = true;
				m_connectted = true;
				m_status = Connected;
				if (Global.hadStartRun == false) {
					//start heart beat thread
					new Thread(this).start();
					Global.hadStartRun = true;
				}
			}
			else if(data[136] == 1)
			{
				System.out.println("REGISTER_ACK: RNode_REG_NOT_REG.");
				Global.hadRcvRegAck = false;
				RNode_sendRegToHNode();
			}
			else if(data[136] == 2)
			{
				System.out.println("REGISTER_ACK: RNode_REG_FAIL.");
				Global.hadRcvRegAck = false;
				RNode_sendRegToHNode();
			}
		}
		else if(data[133] == HeferPeer_MATCH_START )
		{
			if(Global.hadSendWhereIs == true)
			{
				Global.hnodereceivewhereis = true;
				System.out.println("got HeferPeer_MATCH_START msg from HNODE ,data[133]:"+data[133] );
				System.out.println("got HeferPeer_MATCH_START msg from HNODE ,data[136] is p48MatchStart->u1Count:"+data[136] );
				// match start to peer-node
				if(data[136] == 1)
				{
					//byte []  tryMatchMsg = new byte[218];
					final byte []  tryMatchMsg = new byte[218];
					String heferId = Global.heferid;//length:64  大网id：172M8JQj7hh1Uf1sYvTf8NtT9vwxJTbRXg
					byte[] strHeferId  = heferId.getBytes();//walletid
					System.arraycopy(strHeferId, 0, tryMatchMsg, 0,strHeferId.length); //walletid
					int tNodeid = Global.u2RNodeId;//u2
					byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
					System.arraycopy(u2RNodeId, 0, tryMatchMsg, 64,u2RNodeId.length);
					//need modify
					byte[] strToHeferId = new byte[64];
					System.arraycopy(data, 143, strToHeferId, 0,64);
					System.arraycopy(strToHeferId, 0, tryMatchMsg, 66,strToHeferId.length);
					//save to global
					System.arraycopy(strToHeferId, 0, Global.matchedToHeferId, 0,strToHeferId.length);
					//header long is 136 ,137 is count,138 is tonodeaddr,142 is to node port,144 is to heferid, 208 is tou2nodeid,210 is u4subnet,214 is u4subnetmask,218 is u1NatType
					byte[] b_u2ToRNodeId =  new byte[2];
					System.arraycopy(data, 207, b_u2ToRNodeId, 0,2);
					System.arraycopy(b_u2ToRNodeId, 0, tryMatchMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
					//save to global
					System.arraycopy(b_u2ToRNodeId, 0, Global.matched_b_u2ToRNodeId, 0,b_u2ToRNodeId.length);
					int rnodeid =  ByteConvert.bytesToUshort(Global.matched_b_u2ToRNodeId);
					System.out.println("Global.matched_b_u2ToRNodeId: "+ rnodeid);
					tryMatchMsg[132] = 0; //Always be 0
					tryMatchMsg[133] = HeferPeer_MATCH_TRY;//u1Type
					int u2Seq = Global.u1Tried;//init is 10
					byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
					System.arraycopy(b_u2Seq, 0, tryMatchMsg, 134,b_u2Seq.length); //u2Seq
					//peer ip
					byte[] b_u4Addr = new byte[4];
					System.arraycopy(data, 137, b_u4Addr, 0,4);
					//InetAddress PeerNode = null;
					try {
						Global.PeerNode = InetAddress.getByAddress(b_u4Addr);
					} catch (UnknownHostException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					//peer port
					System.arraycopy(data, 141, Global.b_u2ToRNodePort, 0,2);
					Global.toRNodePeerPort = ByteConvert.bytesToUshort(Global.b_u2ToRNodePort);
					Thread thread = new Thread(){
						@Override
						public void run(){
							super.run();
							//int PeerPort = ByteConvert.bytesToUshort(Global.b_u2ToRNodePort);
							for(int i = 0;i < 10;i++)
							{
								if(Global.RcvDefaultNodeTryOrKeepLive == false)
								{
									sendUdpMessage (tryMatchMsg, Global.PeerNode, Global.toRNodePeerPort);
									System.out.println("send tryMatchMsg  msg to Rnode 34 ,PeerPort:" +Global.toRNodePeerPort);
									try {
										Thread.sleep (1 * 1000);
									} catch (InterruptedException e) {
										// TODO Auto-generated catch block
										e.printStackTrace();
									}
								}
							}
						}
					};
					thread.start();
				}//end if count == 1
				else if(data[136] == 0)
				{
					System.out.println("got HeferPeer_MATCH_START msg from HNODE ,data[136] is 0" );
				}
				else
				{
					System.out.println("data[136] is not 0,1 " );
				}
			}
		}
		else if(data[133] == HeferPeer_KEEPALIVE_REQ)//52 is 33 send heartbeat
		{
			System.out.println("got HeferPeer_KEEPALIVE_REQ  msg from Rnode "+Global.u2DefaultRNodeId );
			Global.RcvDefaultNodeTryOrKeepLive = true;
			Global.defaultNodeMatched = true;
			// send matchdone to H-node
			byte []  matchdoneMsg = new byte[136+67];
			String heferId = Global.heferid;//length:64
			byte[] strHeferId  = heferId.getBytes();	//walletid
			System.arraycopy(strHeferId, 0, matchdoneMsg, 0,strHeferId.length); //walletid
			int tNodeid = Global.u2RNodeId;//u2
			byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
			System.arraycopy(u2RNodeId, 0, matchdoneMsg, 64,u2RNodeId.length);
			byte[] strToHeferId = new byte[64];
			System.arraycopy(strToHeferId, 0, matchdoneMsg, 66,strToHeferId.length);
			int u2ToRNodeId = 0;
			byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
			System.arraycopy(b_u2ToRNodeId, 0, matchdoneMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
			matchdoneMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
			matchdoneMsg[133] = HeferPeer_MATCH_DONE;//u1Type
			int u2Seq = 0;//u2
			byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
			System.arraycopy(b_u2Seq, 0, matchdoneMsg, 134,b_u2Seq.length); //u2Seq
			matchdoneMsg[136] = HeferCloudNat_VISIBLE;//
			System.arraycopy(Global.matchedToHeferId, 0, matchdoneMsg, 137,Global.matchedToHeferId.length); //64
			System.arraycopy(Global.matched_b_u2ToRNodeId, 0, matchdoneMsg, 137+64,Global.matched_b_u2ToRNodeId.length); //64
			InetAddress HNode = null;
			try {
				HNode = InetAddress.getByName(Global.strHNodeIp);
			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(Global.HNodeRcvMachDone == false)
			{
				sendUdpMessage (matchdoneMsg, HNode, Global.u2HNodePort);
				System.out.println("send HeferPeer_MATCH_DONE  msg TO  HNODE " );
			}
		}
		else if(data[133] == HeferPeer_MATCH_TRY )
		{
			System.out.println("got HeferPeer_MATCH_TRY  msg from Rnode "+Global.u2DefaultRNodeId );
			Global.RcvDefaultNodeTryOrKeepLive = true;
			Global.defaultNodeMatched = true;
			// send matchdone to H-node
			byte []  matchdoneMsg = new byte[136+67];
			String heferId = Global.heferid;//length:64
			byte[] strHeferId  = heferId.getBytes();	//walletid
			System.arraycopy(strHeferId, 0, matchdoneMsg, 0,strHeferId.length); //walletid
			int tNodeid = Global.u2RNodeId;//u2
			byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
			System.arraycopy(u2RNodeId, 0, matchdoneMsg, 64,u2RNodeId.length);
			byte[] strToHeferId = new byte[64];
			System.arraycopy(strToHeferId, 0, matchdoneMsg, 66,strToHeferId.length);
			int u2ToRNodeId = 0;
			byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
			System.arraycopy(b_u2ToRNodeId, 0, matchdoneMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
			matchdoneMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
			matchdoneMsg[133] = HeferPeer_MATCH_DONE;//u1Type
			int u2Seq = 0;//u2
			byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
			System.arraycopy(b_u2Seq, 0, matchdoneMsg, 134,b_u2Seq.length); //u2Seq
			matchdoneMsg[136] = HeferCloudNat_VISIBLE;//
			System.arraycopy(Global.matchedToHeferId, 0, matchdoneMsg, 137,Global.matchedToHeferId.length); //64
			System.arraycopy(Global.matched_b_u2ToRNodeId, 0, matchdoneMsg, 137+64,Global.matched_b_u2ToRNodeId.length); //64
			InetAddress HNode = null;
			try {
				HNode = InetAddress.getByName(Global.strHNodeIp);
			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(Global.HNodeRcvMachDone == false)
			{
				sendUdpMessage (matchdoneMsg, HNode, Global.u2HNodePort);
				System.out.println("send HeferPeer_MATCH_DONE  msg TO  HNODE " );
			}
		}
		else if(data[133] == HeferPeer_MATCH_DONE_CONFIRM)
		{
			System.out.println("got msg is :HeferPeer_MATCH_DONE_CONFIRM" );
			Global.HNodeRcvMachDone = true;
		}
		else if(data[133] == HeferPeer_MATCH_ROUTE_IND)
		{
			System.out.println("got msg is : HeferPeer_MATCH_ROUTE_IND" );
			//save route ind to link
			HeferMsg_PeerRouteInd.u1Result = 1;//when tun receive pack forward
			System.arraycopy(data, 137, HeferMsg_PeerRouteInd.RouteIndCodeStream, 0,77);//struct HeferMsg_PeerRouteInd
			//save over
		}
		else if(data[133] == HeferPeer_DATA_IND)//nowrussian
		{
			//decrypt data then write tun
			//aesCbcNoPaddingDecrypt(byte[] sSrc, String aesKey, String aesIV)
			//send msg to tun
			if(Global.vpnFileDescriptor == null)
			{
				System.out.println("Global.vpnFileDescriptor is null,can't write");
			}
			else
			{
				byte [] data_dec = new byte[data.length-136];
				System.arraycopy(data, 136, data_dec, 0,data_dec.length);//struct HeferMsg_PeerRouteInd
				byte [] data_deced = AesEncryptUtil.aesCbcNoPaddingDecrypt(data_dec, Global.aesKey,Global.aesIv);
				byte[] b_u2MsgLong =  new byte[2];
				System.arraycopy(data_deced, 0,b_u2MsgLong, 0,b_u2MsgLong.length);
				int longOfMsg =  ByteConvert.bytesToUshort(b_u2MsgLong);
				longOfMsg = longOfMsg - 2;
				byte [] data_write = new byte[longOfMsg];
				System.arraycopy(data_deced, 6,data_write, 0,data_write.length);
				//FileChannel vpnOutput = new FileOutputStream(Global.vpnFileDescriptor).getChannel();
				ByteBuffer bufferFromNetwork = ByteBuffer.wrap(data_write);
				try {
					Global.vpnOutput.write(bufferFromNetwork);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		else
		{
			System.out.println("got other msg from HNODE or peerNode ,data[133]:"+data[133] );
			System.out.println("got other msg from HNODE or peerNode,data.length:"+data.length );
		}

	}

	public void sendTunMessage(byte[] data)
	{
		//m_tunRecvSocket.send(data);
		System.out.println("send message to Tun");
	}
	public void onTunMessage(byte[] data)
	{
		System.out.println("got data from TUN");
	}
	//Heart beat thread
	public void run()
	{
		//Send heartbeat
		while (m_status == Connected)//when received regiser ack
		{
			//sendUdpMessage(heartbeat);
			RNode_sendHeferHeartbeatToHNode(Global.u2HNodeHbSeq++);
			//System.out.println("send a heart beat to Hnode,heartbeat seq:"+Global.u2HNodeHbSeq);
			//send heartbeat to rnode 33
			if(Global.defaultNodeMatched == true)
			{
				//private void RNode_sendHeferHeartbeatToDefaultNode(InetAddress NodeAddr,PeerPort)
				RNode_sendHeferHeartbeatToDefaultNode(Global.PeerNode,Global.toRNodePeerPort);
			}
			//10s every time
			try {
				Thread.sleep (10 * 1000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}
	/*
	//main process
	public static void main(String[] args) throws IOException, InterruptedException
	{
		T t = new T(getApplicationContext());
		byte ip[] = new byte[] { 0, 0, 0, 0};
		InetAddress expectAddress = InetAddress.getByAddress(ip);
		System.out.println("===network.b.T is started.===");
	            t.join("172M8JQj7hh1Uf1sYvTf8NtT9vwxJTbRXg", "172M8JQj7hh1Uf1sYvTf8NtT9vwxJT1234",
			expectAddress, 32);
		System.out.println("connect to server...");
		Thread.sleep (30 * 1000);
		t.leave();
		System.out.println("===network.b.T closed.===");
	}
	*/
} //class T end

//vpn service
public class LocalVPNService extends VpnService
{
	private static final String TAG = LocalVPNService.class.getSimpleName();
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
	//T var
	static T t = new T();
	// public static  T t = new T();
	@Override
	public void onCreate()
	{
		super.onCreate();
		//read file from local: idAndKey.txt
		FileInputStream fis;
		String result = "";
		try {
			fis = openFileInput("idAndKey.txt");
			int length = fis.available();
			byte[] buffer = new byte[length];
			fis.read(buffer);
			result = EncodingUtils.getString(buffer,"UTF-8");
			fis.close();
			System.out.println("read idAndKey from walletid.txt:\n"+ result);
			Global.idAndKey = result;
			t.parseWalletid(result);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.out.println("===read idAndKey.txt is not exit===");
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//read file from local: para.txt
		FileInputStream fis1;
		String result1 = "";
		try {
			fis1 = openFileInput("para.txt");
			int length = fis1.available();
			byte[] buffer = new byte[length];
			fis1.read(buffer);
			result1 = EncodingUtils.getString(buffer,"UTF-8");
			fis1.close();
			System.out.println("read para from para.txt:\n"+ result1);
			Global.configPara = result1;
			t.parsePara(result1);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.out.println("===read para.txt is not exit===");
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if(Global.idAndKey == null && Global.configPara == null)
		{
			System.out.println("=== built new thread so get para from network ===");
			//first start T ,maybe transfer activity later
			//if para not exist,start the thread
			Thread thread = new Thread(){
				@Override
				public void run(){
					super.run();
					t.getParaFromNet();
					System.out.println("connect to server...");

				}
			};
			thread.start();
			try {
				thread.join();
			} catch (Exception e) {}
		}

		Thread thread = new Thread(){
			@Override
			public void run(){
				super.run();
				byte ip[] = new byte[] { 0, 0, 0, 0};
				InetAddress expectAddress = null;
				try {
					expectAddress = InetAddress.getByAddress(ip);
				} catch (UnknownHostException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				System.out.println("===network.b.T is started.===");
				t.join("172M8JQj7hh1Uf1sYvTf8NtT9vwxJTbRXg", "172M8JQj7hh1Uf1sYvTf8NtT9vwxJT1234",expectAddress, 32);
			}
		};
		thread.start();
		try {
			thread.join();
		} catch (Exception e) {}

		//sleep 3 second then start VPN
		try {
			Thread.sleep (2 * 1000);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		//write file to local:idAndKey.txt
		FileOutputStream fos;
		try {
			fos = openFileOutput("idAndKey.txt",MODE_PRIVATE);
			byte[] buffer = Global.idAndKey.getBytes();
			fos.write(buffer);
			fos.flush();
			System.out.println("Write Global.idAndKey to file:idAndKey.txt:"+ Global.idAndKey );
			fos.close();
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//write file to local:para.txt
		FileOutputStream fos1;
		try {
			fos1 = openFileOutput("para.txt",MODE_PRIVATE);
			byte[] buffer = Global.configPara.getBytes();
			fos1.write(buffer);
			fos1.flush();
			System.out.println("Write Global.configPara to file:para.txt:"+Global.configPara);
			fos1.close();
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//protect m_udpSocket not be block by tun
		protect(Global.m_udpSocket);
		//start VPN
		isRunning = true;
		Global.VPN_ADDRESS = Global.strLanIp;
		setupVPN();
		try
		{
			udpSelector = Selector.open();
			tcpSelector = Selector.open();
			deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<Packet>();
			deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<Packet>();
			networkToDeviceQueue = new ConcurrentLinkedQueue<ByteBuffer>();
			executorService = Executors.newFixedThreadPool(5);
			executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
			executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
			executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector));
			executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, this));
			//build vpn
			Global.vpnFileDescriptor = vpnInterface.getFileDescriptor();
			executorService.submit(new VPNRunnable(Global.vpnFileDescriptor,
					deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
			LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
			Log.i(TAG, "Started");
			//protect(t.m_udpSocket);
		}
		catch (IOException e)
		{
			// TODO: Here and elsewhere, we should explicitly notify the user of any errors
			// and suggest that they stop the service, since we can't do it ourselves
			Log.e(TAG, "Error starting service", e);
			cleanup();
		}
	}
	private void setupVPN()
	{
		if (vpnInterface == null)
		{
			Builder builder = new Builder();
			System.out.println(" Global.VPN_ADDRESS:"+Global.VPN_ADDRESS);
			builder.addAddress(Global.VPN_ADDRESS, 32);
			builder.addRoute(VPN_ROUTE, 0);
			builder.setMtu(1300);
			builder.addDnsServer("8.8.8.8");//need read from config msg
			vpnInterface = builder.setSession(getString(R.string.AppName)).setConfigureIntent(pendingIntent).establish();
		}
		// protect(1);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		return START_NOT_STICKY;
		//return START_STICKY;
	}

	public static boolean isRunning()
	{
		return isRunning;
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();
		isRunning = false;
		executorService.shutdownNow();
		cleanup();
		Log.i(TAG, "Stopped");
	}

	private void cleanup()
	{
		deviceToNetworkTCPQueue = null;
		deviceToNetworkUDPQueue = null;
		networkToDeviceQueue = null;
		ByteBufferPool.clear();
		closeResources(udpSelector, tcpSelector, vpnInterface);
	}

	// TODO: Move this to a "utils" class for reuse
	private static void closeResources(Closeable... resources)
	{
		for (Closeable resource : resources)
		{
			try
			{
				resource.close();
			}
			catch (IOException e)
			{
				// Ignore
			}
		}
	}

	private static class VPNRunnable implements Runnable
	{
		private static final String TAG = VPNRunnable.class.getSimpleName();
		private FileDescriptor vpnFileDescriptor;
		private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
		private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
		private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
		public VPNRunnable(FileDescriptor vpnFileDescriptor,
						   ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
						   ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
						   ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue)
		{
			this.vpnFileDescriptor = vpnFileDescriptor;
			this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
			this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
			this.networkToDeviceQueue = networkToDeviceQueue;
		}
		@Override
		public void run()
		{
			FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();//vpnInterface.getFileDescriptor()
			Global.vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

			try
			{
				ByteBuffer bufferToNetwork = null;
				boolean dataSent = true;
				boolean dataReceived;
				while (!Thread.interrupted())
				{
					if (dataSent)
						bufferToNetwork = ByteBufferPool.acquire();
					else
						bufferToNetwork.clear();
					//  Block when not connected
					int readBytes = vpnInput.read(bufferToNetwork);
					if (readBytes > 0)
					{
						dataSent = true;
						bufferToNetwork.flip();
						if(Global.hnodereceivewhereis == false && Global.hadRcvRegAck == true)
						{
							//RECIEVE TUN MSG THEN SEND HeferPeer_WHERE_IS_PEER_REQ TO HNODE
							byte []  whereIsMsg = new byte[142];
							String heferId = Global.heferid;//length:64
							byte[] strHeferId  = heferId.getBytes();//walletid
							System.arraycopy(strHeferId, 0, whereIsMsg, 0,strHeferId.length); //walletid
							int tNodeid = Global.u2RNodeId;//u2
							byte[] u2RNodeId = ByteConvert.ushortToBytes(tNodeid);
							System.arraycopy(u2RNodeId, 0, whereIsMsg, 64,u2RNodeId.length);
							byte[] strToHeferId = new byte[64];
							System.arraycopy(strToHeferId, 0, whereIsMsg, 66,strToHeferId.length);
							int u2ToRNodeId = 0;
							byte[] b_u2ToRNodeId = ByteConvert.ushortToBytes(u2ToRNodeId);
							System.arraycopy(b_u2ToRNodeId, 0, whereIsMsg, 130,b_u2ToRNodeId.length); // RNode_u2HNodeId
							whereIsMsg[132] = 0;//regReqMsg.u1Version = 0; //Always be 0
							whereIsMsg[133] = 41;//u1Type = HeferPeer_WHERE_IS_PEER_REQ
							int u2Seq = 0;//u2
							byte[] b_u2Seq  = ByteConvert.ushortToBytes(u2Seq);
							System.arraycopy(b_u2Seq, 0, whereIsMsg, 134,b_u2Seq.length); //u2Seq
							long u4ReqAddr = 0;
							byte[] b_u4ReqAddr =  ByteConvert.uintToBytes(u4ReqAddr);
							System.arraycopy(b_u4ReqAddr, 0, whereIsMsg, 136,b_u4ReqAddr.length);
							int u2DefaultRNodeId = Global.u2DefaultRNodeId;
							byte[] b_u2DefaultRNodeId  = ByteConvert.ushortToBytes(u2DefaultRNodeId);
							System.arraycopy(b_u2DefaultRNodeId, 0, whereIsMsg, 140,b_u2DefaultRNodeId.length); //u2Seq
							//dst ip:172.217.26.36
							InetAddress HNode = InetAddress.getByName(Global.strHNodeIp);
							t.sendUdpMessage (whereIsMsg, HNode, Global.u2HNodePort);
							System.out.println("send whereis to hnode!");
							Global.hadSendWhereIs = true;
						}
						if(HeferMsg_PeerRouteInd.u1Result == 1)//nowrussian
						{
							//data encrypt then send rnode34
							int remaining = bufferToNetwork.remaining();
							byte[] message = new byte[remaining+6+136];
							bufferToNetwork.get(message, 6+136, remaining);//buffer读取后，bufferToNetwork.remaining()清零
							//1,2byte
							int longOfmessage = remaining+2;
							byte[] b_u2longOfmessage  = ByteConvert.ushortToBytes(longOfmessage);
							System.arraycopy(b_u2longOfmessage, 0, message, 136,b_u2longOfmessage.length); //137改成136
							//5,6byte
							longOfmessage = remaining;
							byte[] b_u2longOfmessage56  = ByteConvert.ushortToBytes(longOfmessage);
							System.arraycopy(b_u2longOfmessage56, 0, message, 140,b_u2longOfmessage56.length); //141改成140
							Global.hefer_header[133] = 51;//HeferPeer_DATA_IND;
							//to rnodeid
							System.arraycopy(HeferMsg_PeerRouteInd.RouteIndCodeStream, 0, Global.hefer_header, 130,2);
							//to nextpop heferid
							System.arraycopy(HeferMsg_PeerRouteInd.RouteIndCodeStream, 10, Global.hefer_header, 66,64);
							//lpq;
							//System.arraycopy(Global.hefer_header, 0, message, 0,136); //u2Seq
							byte[] message_payload =  new byte[remaining+6];
							System.arraycopy(message, 136, message_payload, 0,remaining+6);
							byte[] message_payload_enc = AesEncryptUtil.aesCbcNoPaddingEncrypt(message_payload, Global.aesKey,  Global.aesIv);
							byte[] message_send = new byte[136+message_payload_enc.length ];
							System.arraycopy(Global.hefer_header, 0, message_send, 0,136); //u2Seq
							System.arraycopy(message_payload_enc, 0, message_send, 136,message_payload_enc.length);
							//t.sendUdpMessage (message_send, RNode33, 56789);
							t.sendUdpMessage (message_send, Global.PeerNode, Global.toRNodePeerPort);
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
					}
					else//readbyte<0
					{
						dataSent = false;
					}
                    /*
                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null)
                    {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining())
                        	Global.vpnOutput.write(bufferFromNetwork);
                        dataReceived = true;
                        ByteBufferPool.release(bufferFromNetwork);
                    }
                    else
                    {
                        dataReceived = false;
                    }
                    */
					// TODO: Sleep-looping is not very battery-friendly, consider blocking instead
					// Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
					// if (!dataSent && !dataReceived)
					if (!dataSent )
						Thread.sleep(10);
				}
			}
			catch (InterruptedException e)
			{
				Log.i(TAG, "Stopping");
			}
			catch (IOException e)
			{
				Log.w(TAG, e.toString(), e);
			}
			finally
			{
				closeResources(vpnInput, Global.vpnOutput);
			}
		}
	}
	public static String bytesToHexFun3(byte[] bytes) {
		StringBuilder buf = new StringBuilder(bytes.length * 2);
		for(byte b : bytes) {
			buf.append(String.format("%02x", new Integer(b & 0xff)));
		}
		return buf.toString();
	}

}
