// Compile-time stubs for the device's /system/framework/qcom.fmradio.jar.
//
// The tuner is the Qualcomm WCN SoC FM core, not a discrete chip: on-device logging shows
// fm_hci / radio_helium / android_hardware_fm carrying HCI opcodes over the shared BT transport,
// and FmReceiver.getSocName() returns "cherokee". HiBy layers its own V4L2 hooks
// (FmReceiverJNI.*V4L2*) on top of that same jar.
//
// Signatures were dumped from the device jar with dexdump on 2026-08-25 and extended with
// baksmali on 2026-10-09 — keep them byte-exact. This module is compileOnly for :fmradio; at
// runtime the real classes come from the framework via <uses-library android:name="qcom.fmradio"/>.
plugins { id("java-library") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    // android.content.Context in signatures — compile against the platform jar only.
    compileOnly(files("/home/reaver/Android/Sdk/platforms/android-34/android.jar"))
}
