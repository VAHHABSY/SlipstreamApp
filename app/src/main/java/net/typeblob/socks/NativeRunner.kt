object NativeRunner {
    init {
        System.loadLibrary("native_runner")
    }

    external fun runSlipstream(libPath: String, domain: String, resolvers: String, port: Int, logFilePath: String): Int
}