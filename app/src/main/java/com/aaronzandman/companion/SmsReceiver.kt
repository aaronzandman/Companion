package com.aaronzandman.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Runs when a text arrives. Only texts in the dispatch format are kept; everything else is ignored. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (Store.get(ctx, "smsOn", "1") != "1") return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for ((sender, parts) in msgs.groupBy { it.originatingAddress ?: "" }) {
            val filter = Store.get(ctx, "senderFilter", "").trim()
            if (filter.isNotEmpty() && !sender.contains(filter, ignoreCase = true)) continue
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val found = DispatchParser.parse(body, parts.first().timestampMillis, sender)
            if (found.isNotEmpty() && Store.addDispatches(ctx, found) > 0) {
                Notify.newDispatch(ctx, found)
                if (Sap.configured(ctx) && Store.get(ctx, "sapAutoSms", "1") == "1") Notify.syncSap(ctx)
            }
        }
    }
}
