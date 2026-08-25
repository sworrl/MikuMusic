package qcom.fmradio;
public interface FmRxEvCallbacks {
    void FmRxEvEnableReceiver();
    void FmRxEvDisableReceiver();
    void FmRxEvRadioReset();
    void FmRxEvRadioTuneStatus(int freq);
    void FmRxEvRdsLockStatus(boolean rdsAvail);
    void FmRxEvStereoStatus(boolean stereo);
    void FmRxEvServiceAvailable(boolean service);
    void FmRxEvSearchInProgress();
    void FmRxEvSearchCancelled();
    void FmRxEvSearchComplete(int freq);
    void FmRxEvSearchListComplete();
    void FmRxEvRdsGroupData();
    void FmRxEvRdsPsInfo();
    void FmRxEvRdsRtInfo();
    void FmRxEvRdsAfInfo();
    void FmRxEvRTPlus();
    void FmRxEvERTInfo();
    void FmRxEvECCInfo();
    void FmRxEvEnableSlimbus(int status);
    void FmRxEvEnableSoftMute(int status);
}
