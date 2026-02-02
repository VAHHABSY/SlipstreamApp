package net.typeblob.socks

object NativeRunner {
    init {
        System.loadLibrary("native_runner")
    }

    external fun runSlipstream(domain: String, resolvers: String, port: Int): Int
}