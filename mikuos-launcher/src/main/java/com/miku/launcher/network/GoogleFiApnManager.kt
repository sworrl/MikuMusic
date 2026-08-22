package com.miku.launcher.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import com.miku.launcher.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hatsune Miku Prebundled Google Fi & T-Mobile APN Auto-Provisioner.
 * Automatically injects and binds 'h2g2' and 'alpha' carrier configs into the OS
 * so Google Fi 4G/LTE mobile data connects out of the box with zero manual config.
 */
object GoogleFiApnManager {
    private const val TAG = "GoogleFiApn"
    private val CARRIERS_URI: Uri = Uri.parse("content://telephony/carriers")

    fun autoProvisionIfGoogleFi(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val operatorName = tm?.simOperatorName ?: ""
                val operatorNumeric = tm?.simOperator ?: ""

                // Ensure mobile data and roaming are activated
                RootShell.execFast(
                    "svc data enable; " +
                    "settings put global mobile_data 1; " +
                    "settings put global mobile_data0 1; " +
                    "settings put global data_roaming 1; " +
                    "settings put global data_roaming0 1"
                )

                // Inject Google Fi APNs for numeric 310240 and 310260
                val apns = listOf(
                    Triple("Google Fi", "h2g2", "310240"),
                    Triple("Google Fi", "h2g2", "310260"),
                    Triple("Google Fi IMS", "ims", "310240"),
                    Triple("Google Fi IMS", "ims", "310260")
                )

                for ((name, apn, num) in apns) {
                    val mcc = num.substring(0, 3)
                    val mnc = num.substring(3)
                    RootShell.execFast(
                        "content insert --uri content://telephony/carriers " +
                        "--bind name:s:'$name' " +
                        "--bind numeric:s:'$num' " +
                        "--bind mcc:s:'$mcc' " +
                        "--bind mnc:s:'$mnc' " +
                        "--bind apn:s:'$apn' " +
                        "--bind type:s:'default,mms,supl,dun,hipri,ia' " +
                        "--bind protocol:s:'IPV4V6' " +
                        "--bind roaming_protocol:s:'IPV4V6' " +
                        "--bind carrier_enabled:i:1 " +
                        "--bind user_visible:i:1 " +
                        "--bind user_editable:i:1 " +
                        "--bind current:i:1 2>/dev/null"
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to auto-provision Google Fi APN", t)
            }
        }
    }
}
