package qcom.fmradio;
public class FmTransceiver {
    public static final int FMState_Turned_Off = 0;
    public static final int FMState_Rx_Turned_On = 1;
    public static final int FMState_Tx_Turned_On = 2;
    public static final int FMState_Srch_InProg = 3;
    public static final int subPwrLevel_Nominal = 4;
    public FmTransceiver() { throw new RuntimeException("stub"); }
    public boolean enable(FmConfig config, int device) { throw new RuntimeException("stub"); }
    public boolean disable() { throw new RuntimeException("stub"); }
    public boolean configure(FmConfig config) { throw new RuntimeException("stub"); }
    public boolean setStation(int frequencyKHz) { throw new RuntimeException("stub"); }
    public boolean getInternalAntenna() { throw new RuntimeException("stub"); }
    public boolean setInternalAntenna(boolean intAnt) { throw new RuntimeException("stub"); }
    public void setNotchFilter(boolean value) { throw new RuntimeException("stub"); }
    public int getFMPowerState() { throw new RuntimeException("stub"); }
    public void setFMPowerState(int state) { throw new RuntimeException("stub"); }
    public boolean setRDSGrpMask(int mask) { throw new RuntimeException("stub"); }
}
