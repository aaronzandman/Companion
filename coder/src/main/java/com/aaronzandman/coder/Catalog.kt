package com.aaronzandman.coder

/**
 * The settings this tool is aimed at. Each entry names the module it lives in and what the change does.
 *
 * Deliberately no hard-coded as-built address/value is shipped: Ford as-built addressing varies by
 * model year, market and VIN, and a wrong write can disable a module. The intended workflow is:
 *   1) load the module here, 2) find the exact as-built address/value for YOUR VIN from a trusted
 *   source (FORScan's as-built view, or a verified Transit spreadsheet for your year),
 *   3) read that DID to see the current bytes, 4) change only the documented bit/byte, 5) write.
 */
object Catalog {
    data class Item(
        val title: String,
        val group: String,
        val reqId: String,     // module request CAN id
        val forscan: String,   // where it lives / how FORScan labels it
        val did: String,       // left blank on purpose — fill after you confirm it for your VIN
        val what: String       // what the change does and what to look for
    ) {
        fun detail(): String = buildString {
            append(what); append("\n\n")
            append("Module: $reqId (${group.substringBefore(" ")}).\n")
            append("Where: $forscan\n\n")
            append("Before writing: read the exact as-built block for YOUR VIN, change only the documented bit, keep the backup this app makes. Addresses differ by model year — don't assume a value from another truck.")
        }
    }

    val ALL = listOf(
        Item("Double honk on leave", "BCM & Convenience", "726",
            "BCM as-built — remote-start / walk-away horn chirp bit",
            "",
            "Disables the double horn chirp that sounds when you walk away from the van with the fob while the engine is running."),
        Item("12V power point timeout", "BCM & Convenience", "726",
            "BCM as-built — accessory delay / 12V socket power-down timer",
            "",
            "Adjusts or disables the timeout on the 12V sockets so they stay powered longer (to charge gear) or shut off immediately (to protect the battery)."),
        Item("Daytime running lights (DRL)", "BCM & Convenience", "726",
            "BCM as-built — DRL mode (off / with parking lamps / in-gear only)",
            "",
            "Changes DRL behavior — e.g. allow the parking lamps on with the DRLs, or run DRLs only once shifted into gear."),
        Item("Global windows (fob)", "BCM & Convenience", "726",
            "BCM as-built — remote window open/close enable",
            "",
            "Lets a held unlock/lock on the key fob roll the front windows down/up."),
        Item("Autofold mirrors", "BCM & Convenience", "726",
            "BCM as-built — mirror fold-on-lock enable (needs power-folding mirrors)",
            "",
            "Folds the side mirrors in when the van locks and deploys them when it unlocks. Requires power-folding mirror hardware."),
        Item("Live temperature readouts", "IPC & Infotainment", "720",
            "IPC as-built — digital temperature overlay on coolant/trans gauges",
            "",
            "Adds exact digital temperature numbers above the coolant and transmission gauges instead of the generic bar graphic."),
        Item("Menu control in motion", "IPC & Infotainment", "7D0",
            "APIM (SYNC) as-built — in-motion lockout for nav/menus",
            "",
            "Unlocks navigation destination entry / detailed menus for a passenger while the vehicle is moving.")
    )
}
