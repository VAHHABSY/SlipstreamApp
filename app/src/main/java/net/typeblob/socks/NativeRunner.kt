package net.typeblob.socks

/**
 * Native runner for loading and executing libslipstream.so via JNI
 */
object NativeRunner {
    init {
        System.loadLibrary("native_runner")
    }

    /**
     * Load and run slipstream library via JNI
     * 
     * @param libPath Path to libslipstream.so
     * @param domain Domain to use
     * @param resolvers DNS resolvers
     * @param port SOCKS port
     * @return 0 on success, negative on error
     */
    external fun runSlipstream(libPath: String, domain: String, resolvers: String, port: Int): Int
}
