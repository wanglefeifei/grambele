package org.telegram.bnet.bean;

import java.io.Serializable;

public class BnetServiceJoinParams implements Serializable {
    public BnetServiceJoinParams(String nWalletAddr, String dWalletAddr, String deviceAddr, int maskBit) {
        this.nWalletAddr = nWalletAddr;
        this.dWalletAddr = dWalletAddr;
        this.deviceAddr = deviceAddr;
        this.maskBit = maskBit;
    }

    public String getnWalletAddr() {
        return nWalletAddr;
    }

    public void setnWalletAddr(String nWalletAddr) {
        this.nWalletAddr = nWalletAddr;
    }

    public String getdWalletAddr() {
        return dWalletAddr;
    }

    public void setdWalletAddr(String dWalletAddr) {
        this.dWalletAddr = dWalletAddr;
    }

    public String getDeviceAddr() {
        return deviceAddr;
    }

    public void setDeviceAddr(String deviceAddr) {
        this.deviceAddr = deviceAddr;
    }

    public int getMaskBit() {
        return maskBit;
    }

    public void setMaskBit(int maskBit) {
        this.maskBit = maskBit;
    }

    private String nWalletAddr, dWalletAddr, deviceAddr;
    private int maskBit;
}
