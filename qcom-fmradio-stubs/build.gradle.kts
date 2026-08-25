// Compile-time stubs for the device's /system/framework/qcom.fmradio.jar (HiBy M500, Si4705
// tuner behind the QTI FM Java API). Signatures were dumped from the device jar with dexdump
// on 2026-08-25 — keep them byte-exact. This module is compileOnly for :fmradio; at runtime the
// real classes come from the framework via <uses-library android:name="qcom.fmradio"/>.
plugins { id("java-library") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    // android.content.Context in signatures — compile against the platform jar only.
    compileOnly(files("/home/reaver/Android/Sdk/platforms/android-34/android.jar"))
}
