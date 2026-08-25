package qcom.fmradio;
import android.content.Context;
/** Signatures dumped from the M500's qcom.fmradio.jar (see module build.gradle.kts). */
public class FmReceiver extends FmTransceiver {
    public static final int FM_RX_SRCH_MODE_SEEK = 0;
    public static final int FM_RX_SRCH_MODE_SCAN = 1;
    public static final int FM_RX_SRCHRDS_MODE_SEEK_PTY = 4;
    public static final int FM_RX_SRCHRDS_MODE_SCAN_PTY = 5;
    public static final int FM_RX_SRCHRDS_MODE_SEEK_PI = 6;
    public static final int FM_RX_SRCHRDS_MODE_SEEK_AF = 7;
    public static final int FM_RX_SEARCHDIR_UP = 1;
    public static final int FM_RX_SEARCHDIR_DOWN = 0;
    public static final int FM_RX_DWELL_PERIOD_0S = 0;
    public static final int FM_RX_DWELL_PERIOD_1S = 1;
    public static final int FM_RX_DWELL_PERIOD_2S = 2;
    public static final int FM_RX_DWELL_PERIOD_3S = 3;
    public static final int FM_RX_DWELL_PERIOD_4S = 4;
    public static final int FM_RX_UNMUTE = 0;
    public static final int FM_RX_MUTE = 1;
    public static final int FM_RX_AUDIO_MODE_STEREO = 0;
    public static final int FM_RX_AUDIO_MODE_MONO = 1;
    public static final int FM_RX_NORMAL_POWER_MODE = 0;
    public static final int FM_RX_LOW_POWER_MODE = 1;
    public static final int FM_RX_RDS_GRP_RT_EBL = 1;
    public static final int FM_RX_RDS_GRP_PS_EBL = 2;
    public static final int FM_RX_RDS_GRP_AF_EBL = 4;
    public static final int FM_RX_RDS_GRP_PS_SIMPLE_EBL = 16;
    public static final int FM_RX_RDS_GRP_ECC_EBL = 32;
    public static final int FM_RX_RDS_GRP_PTYN_EBL = 64;
    public static final int FM_RX_RDS_GRP_RT_PLUS_EBL = 128;
    public FmReceiver() { throw new RuntimeException("stub"); }
    public FmReceiver(String devicePath, FmRxEvCallbacksAdaptor callback) throws InstantiationException { throw new RuntimeException("stub"); }
    public boolean enable(FmConfig configSettings, Context app_context) { throw new RuntimeException("stub"); }
    public boolean disable(Context app_context) { throw new RuntimeException("stub"); }
    public boolean reset() { throw new RuntimeException("stub"); }
    public boolean searchStations(int mode, int dwellPeriod, int direction) { throw new RuntimeException("stub"); }
    public boolean searchStations(int mode, int dwellPeriod, int direction, int pty, int pi) { throw new RuntimeException("stub"); }
    public boolean searchStationList(int mode, int direction, int maximumStations, int pty) { throw new RuntimeException("stub"); }
    public boolean cancelSearch() { throw new RuntimeException("stub"); }
    public boolean setMuteMode(int mode) { throw new RuntimeException("stub"); }
    public boolean setStereoMode(boolean stereoEnable) { throw new RuntimeException("stub"); }
    public boolean setSignalThreshold(int threshold) { throw new RuntimeException("stub"); }
    public int getSignalThreshold() { throw new RuntimeException("stub"); }
    public int getTunedFrequency() { throw new RuntimeException("stub"); }
    public int getRssi() { throw new RuntimeException("stub"); }
    public int getSINR() { throw new RuntimeException("stub"); }
    public int getFMState() { throw new RuntimeException("stub"); }
    public int getSearchState() { throw new RuntimeException("stub"); }
    public int getPowerMode() { throw new RuntimeException("stub"); }
    public boolean setPowerMode(int powerMode) { throw new RuntimeException("stub"); }
    public FmRxRdsData getPSInfo() { throw new RuntimeException("stub"); }
    public FmRxRdsData getRTInfo() { throw new RuntimeException("stub"); }
    public FmRxRdsData getRTPlusInfo() { throw new RuntimeException("stub"); }
    public FmRxRdsData getERTInfo() { throw new RuntimeException("stub"); }
    public FmRxRdsData getECCInfo() { throw new RuntimeException("stub"); }
    public int[] getAFInfo() { throw new RuntimeException("stub"); }
    public boolean registerRdsGroupProcessing(int fmGrpsToProc) { throw new RuntimeException("stub"); }
    public boolean setRdsGroupOptions(int enRdsGrpsMask, int rdsBuffSize, boolean enRdsChangeFilter) { throw new RuntimeException("stub"); }
    public boolean setRawRdsGrpMask() { throw new RuntimeException("stub"); }
    public boolean enableAFjump(boolean enable) { throw new RuntimeException("stub"); }
    public String getSocName() { throw new RuntimeException("stub"); }
    public boolean isRomeChip() { throw new RuntimeException("stub"); }
    public boolean isSmdTransportLayer() { throw new RuntimeException("stub"); }
    public void registerDataConnectionStateListener(Context context) { throw new RuntimeException("stub"); }
    public void unregisterDataConnectionStateListener(Context context) { throw new RuntimeException("stub"); }
    public void EnableSlimbus(int enable) { throw new RuntimeException("stub"); }
    public void EnableSoftMute(int enable) { throw new RuntimeException("stub"); }
}
