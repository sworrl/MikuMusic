package qcom.fmradio;

/**
 * HiBy's V4L2 hooks inside the device's qcom.fmradio.jar.
 *
 * These are NOT part of the upstream CAF FM API. HiBy added a set of public static natives that
 * talk to /dev/radio0 directly, alongside the HCI path that FmReceiver drives. Stock FM2 uses
 * both: the HCI API for enable/tune/seek, and these for mute, antenna and signal quality.
 *
 * Signatures dumped from the device jar with baksmali on 2026-10-09 (qcom/fmradio/FmReceiverJNI).
 * Keep them byte-exact: this module is compileOnly and the real class comes from the framework
 * at runtime via uses-library, so a wrong signature is a NoSuchMethodError on device, not a
 * build failure here.
 *
 * All of these are hidden API. This app may call them because it is platform-signed, which
 * PackageManagerService translates into HIDDEN_API_ENFORCEMENT_DISABLED; stock FM2 relies on
 * exactly the same exemption.
 *
 * Only the members this app actually calls are declared. The jar has more (setFreqNative,
 * getRSSINative, setBandNative and the rest of the HCI natives), but those are package-private
 * and are reached through FmReceiver.
 *
 * Note that the jar is not identical across firmware revisions. The archived copy in
 * m500-system-archive declares a `getInternalAntenna()` that the jar actually on the device does
 * not have, and calling it throws NoSuchMethodError. Anything added here should be checked
 * against the device's own /system/framework/qcom.fmradio.jar, not only the archive.
 */
public class FmReceiverJNI {

    public FmReceiverJNI() { throw new RuntimeException("stub"); }

    /**
     * Driver-level FM mute, 1 = muted. This is the mute stock FM2 actually uses
     * (FmReceiver.setMuteMode goes over HCI and does not gate the HiBy V4L2 audio path).
     * The driver comes up muted, so this has to be cleared once the route is built.
     */
    public static int setV4L2RadioFmMute(int mute) { throw new RuntimeException("stub"); }

    public static int getV4L2RadioFmMute() { throw new RuntimeException("stub"); }

    /** Tuned frequency in V4L2 units of 1/16 kHz (62.5 Hz). Divide by 16 for kHz. */
    public static int getV4L2RadioFrequency() { throw new RuntimeException("stub"); }

    /**
     * Tune, in the same 1/16 kHz units. This is how stock FM2 tunes on this device: its tune()
     * takes the HiBy branch, posts a SetFreqRunnable that calls this, and then synthesises the
     * FmRxEvRadioTuneStatus callback itself. FmReceiver.setStation() is the non-HiBy fallback.
     */
    public static int setV4L2RadioFrequency(int freqV4L2) { throw new RuntimeException("stub"); }

    /**
     * Signal quality, read straight from the tuner. Stock FM2 labels the elements, in order:
     * [0] signal, [1] rssi, [2] snr, [3] multipath, [4] freqOffset, [5] freq, [6] valid.
     * Length is not documented anywhere; treat anything past the end as unavailable.
     */
    public static int[] getV4L2RadioFmSignal() { throw new RuntimeException("stub"); }
}
