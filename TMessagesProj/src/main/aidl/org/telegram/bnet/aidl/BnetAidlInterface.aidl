// BnetAidlInterface.aidl
package org.telegram.bnet.aidl;

// Declare any non-default types here with import statements

interface BnetAidlInterface {
   int create(String nWalletAddr, String masterAddr, int maskBit);

   	//start BNET engine
   	 int join(String nWalletAddr, String dWalletAddr, String deviceAddr, int maskBit);

   	//stop BNET engine
   	 int accept(String deviceAddr, int maskBit);

   	//get wallet balance.
   	 int reject() ;

   	 int leave();
String getRequest();
int getStatus();
void sendUdpMessage(in byte[] data, String to, int port);
void onUdpMessage(in byte[] data);
void sendTunMessage(in byte[] data);
void onTunMessage(in byte[] data);
void CStartService();

}
