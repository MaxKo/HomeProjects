package com.kerberos.radiorocksstreamingapp.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kerberos.radiorocksstreamingapp.data.StreamConstants
import com.kerberos.radiorocksstreamingapp.service.PlaybackService

class BluetoothConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                action = StreamConstants.ACTION_PLAY
            }
            try {
                context.startForegroundService(serviceIntent)
            } catch (_: Exception) {
                context.startService(serviceIntent)
            }
        }
    }
}
