#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>

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
        if (result != 0) {
            LOGE("slipstream_main failed with code: %d", result);
        } else {
            LOGI("slipstream_main completed successfully");
        }
    } else {
        LOGI("slipstream_main not found, trying main function");
        
        // Fallback to main function
        typedef int (*MainFunc)(int, char**);
        MainFunc main_func = reinterpret_cast<MainFunc>(dlsym(handle, "main"));
        
        if (main_func) {
            // Construct argv with mutable copies of all strings
            // We need to create mutable copies because main() may modify argv
            std::string portStr = std::to_string(port);
            
            // Create array of mutable string copies
            char* argv0 = strdup("slipstream");
            char* argv1 = strdup(domain);
            char* argv2 = strdup(resolvers);
            char* argv3 = strdup("--socks-port");
            char* argv4 = strdup(portStr.c_str());
            
            char* args[] = { argv0, argv1, argv2, argv3, argv4, nullptr };
            
            LOGI("Found main, calling with argc=5");
            result = main_func(5, args);
            if (result != 0) {
                LOGE("main failed with code: %d", result);
            } else {
                LOGI("main completed successfully");
            }
            
            // Free the allocated strings
            free(argv0);
            free(argv1);
            free(argv2);
            free(argv3);
            free(argv4);
        } else {
            LOGE("Neither slipstream_main nor main found in library: %s", dlerror());
            result = -2;
        }
    }

    // Don't dlclose - slipstream runs as a server and spawns threads
    // The library needs to remain loaded for the lifetime of the app
    // dlclose(handle);

    env->ReleaseStringUTFChars(jLibPath, libPath);
    env->ReleaseStringUTFChars(jDomain, domain);
    env->ReleaseStringUTFChars(jResolvers, resolvers);

    return result;
}
