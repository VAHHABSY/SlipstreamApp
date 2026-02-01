package net.typeblob.socks

sealed class SlipstreamStatus {
    object Stopped : SlipstreamStatus()
    object Stopping : SlipstreamStatus()
    data class Starting(val message: String = "") : SlipstreamStatus()
    object Running : SlipstreamStatus()
    data class Failed(val error: String) : SlipstreamStatus()
}

sealed class SocksStatus {
    object Stopped : SocksStatus()
    object Stopping : SocksStatus()
    object Waiting : SocksStatus()
    object Running : SocksStatus()
}