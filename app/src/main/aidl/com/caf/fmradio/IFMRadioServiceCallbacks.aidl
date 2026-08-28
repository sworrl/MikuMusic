package com.caf.fmradio;

interface IFMRadioServiceCallbacks {
    void onEnabled();
    void onDisabled();
    void onRadioReset();
    void onTuneStatusChanged();
    void onProgramServiceChanged();
    void onRadioTextChanged();
    void onAlternateFrequencyChanged();
    void onSignalStrengthChanged();
    void onSearchComplete();
    void onSearchListComplete();
    void onMute(boolean isMute);
    void onAudioUpdate(boolean isStereo);
    void onStationRDSSupported(boolean isRDSSupported);
    void onRecordingStopped();
    void onExtenRadioTextChanged();
    void onRecordingStarted();
    void onSeekPrevStation();
    void onSeekNextStation();
    void onA2DPConnectionstateChanged(boolean state);
    void onFmAudioPathStarted();
    void onFmAudioPathStopped();
    void onExtenCountryCodeChanged();
    void getSigThCb(int val, int status);
    void getChDetThCb(int val, int status);
    void DefDataRdCb(int val, int status);
    void getBlendCb(int val, int status);
    void setChDetThCb(int status);
    void DefDataWrtCb(int status);
    void setBlendCb(int status);
    void getStationParamCb(int val, int status);
    void getStationDbgParamCb(int val, int status);
    void changeStation(boolean isNext);
}
