#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>

#define LOG_TAG "NativeRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_net_typeblob_socks_NativeRunner_runSlipstream(
    JNIEnv* env,
    jobject /* this */,
    jstring jLibPath,
    jstring jDomain,
    jstring jResolvers,
    jint jPort) {
    
    const char* libPath = env->GetStringUTFChars(jLibPath, nullptr);
    const char* domain = env->GetStringUTFChars(jDomain, nullptr);
    const char* resolvers = env->GetStringUTFChars(jResolvers, nullptr);
    int port = static_cast<int>(jPort);

    LOGI("Loading library: %s", libPath);
    LOGI("Domain: %s, Resolvers: %s, Port: %d", domain, resolvers, port);

    // Open the library
    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("Failed to dlopen %s: %s", libPath, dlerror());
        env->ReleaseStringUTFChars(jLibPath, libPath);
        env->ReleaseStringUTFChars(jDomain, domain);
        env->ReleaseStringUTFChars(jResolvers, resolvers);
        return -1;
    }

    LOGI("Library loaded successfully");

    int result = -1;

    // Try to resolve slipstream_main first
    typedef int (*SlipstreamMainFunc)(const char*, const char*, int);
    SlipstreamMainFunc slipstream_main = 
        reinterpret_cast<SlipstreamMainFunc>(dlsym(handle, "slipstream_main"));

    if (slipstream_main) {
        LOGI("Found slipstream_main, calling with domain=%s, resolvers=%s, port=%d", 
             domain, resolvers, port);
        result = slipstream_main(domain, resolvers, port);
        LOGI("slipstream_main returned: %d", result);
    } else {
        LOGI("slipstream_main not found, trying main function");
        
        // Fallback to main function
        typedef int (*MainFunc)(int, char**);
        MainFunc main_func = reinterpret_cast<MainFunc>(dlsym(handle, "main"));
        
        if (main_func) {
            // Construct argv
            std::string portStr = std::to_string(port);
            std::vector<const char*> args = {
                "slipstream",
                domain,
                resolvers,
                "--socks-port",
                portStr.c_str(),
                nullptr
            };
            
            LOGI("Found main, calling with argc=%d", (int)args.size() - 1);
            result = main_func(args.size() - 1, const_cast<char**>(args.data()));
            LOGI("main returned: %d", result);
        } else {
            LOGE("Neither slipstream_main nor main found in library: %s", dlerror());
            result = -2;
        }
    }

    // Don't dlclose - the library may have spawned threads
    // dlclose(handle);

    env->ReleaseStringUTFChars(jLibPath, libPath);
    env->ReleaseStringUTFChars(jDomain, domain);
    env->ReleaseStringUTFChars(jResolvers, resolvers);

    return result;
}
