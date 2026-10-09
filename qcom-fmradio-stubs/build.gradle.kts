// Compile-time stubs for the device's /system/framework/qcom.fmradio.jar.
//
// Two FM-capable paths exist on this board. /dev/radio0 is bound to i2c-2 address 0x63, a
// Silicon Labs Si4705, and that is the part that receives. The Qualcomm WCN FM stack is also
// present and answers (fm_hci / radio_helium; getSocName() returns "cherokee", which is the
// Bluetooth SoC name out of bt_configstore). This jar carries the API for both: FmReceiver.*
// speaks HCI to the Qualcomm side, FmReceiverJNI.*V4L2* drives the Si4705.
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
