package net.typeblob.socks

sealed class SlipstreamStatus {
    object Stopped : SlipstreamStatus()
    data class Starting(val message: String) : SlipstreamStatus()
    object Running : SlipstreamStatus()
    object Stopping : SlipstreamStatus()
    data class Failed(val message: String) : SlipstreamStatus()
}

sealed class SocksStatus {
    object Stopped : SocksStatus()
    object Waiting : SocksStatus()
    object Running : SocksStatus()
    object Stopping : SocksStatus()
}