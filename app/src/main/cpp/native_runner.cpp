#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <cstdarg>
#include <unistd.h>

#define LOG_TAG "NativeRunner"

void logToFile(const char* logFilePath, const char* format, ...) {
    FILE* file = fopen(logFilePath, "a");
    if (!file) return;

    time_t now = time(nullptr);
    char timeStr[20];
    strftime(timeStr, sizeof(timeStr), "%Y-%m-%d %H:%M:%S", localtime(&now));
    fprintf(file, "[%s] ", timeStr);

    va_list args;
    va_start(args, format);
    vfprintf(file, format, args);
    va_end(args);

    fprintf(file, "\n");
    fclose(file);
}

#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
    logToFile(logFilePath, __VA_ARGS__); \
} while(0)

#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
    logToFile(logFilePath, __VA_ARGS__); \
} while(0)

extern "C" JNIEXPORT jint JNICALL
Java_net_typeblob_socks_NativeRunner_runSlipstream(
    JNIEnv* env,
    jobject /* this */,
    jstring jLibPath,
    jstring jDomain,
    jstring jResolvers,
    jint jPort,
    jstring jLogFilePath) {
    
    const char* libPath = env->GetStringUTFChars(jLibPath, nullptr);
    const char* domain = env->GetStringUTFChars(jDomain, nullptr);
    const char* resolvers = env->GetStringUTFChars(jResolvers, nullptr);
    int port = static_cast<int>(jPort);
    const char* logFilePath = env->GetStringUTFChars(jLogFilePath, nullptr);

    LOGI("=== Starting runSlipstream ===");
    LOGI("Library path: %s", libPath);
    LOGI("Domain: %s, Resolvers: %s, Port: %d", domain, resolvers, port);
    LOGI("Log file path: %s", logFilePath);

    LOGI("Attempting to dlopen library...");
    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("dlopen FAILED for %s: %s", libPath, dlerror());
        env->ReleaseStringUTFChars(jLibPath, libPath);
        env->ReleaseStringUTFChars(jDomain, domain);
        env->ReleaseStringUTFChars(jResolvers, resolvers);
        env->ReleaseStringUTFChars(jLogFilePath, logFilePath);
        LOGI("=== runSlipstream FAILED (dlopen error) ===");
        return -1;
    }
    LOGI("dlopen SUCCESS: Library loaded at %p", handle);

    int result = -1;

    LOGI("Searching for slipstream_main function...");
    typedef int (*SlipstreamMainFunc)(const char*, const char*, int);
    SlipstreamMainFunc slipstream_main = 
        reinterpret_cast<SlipstreamMainFunc>(dlsym(handle, "slipstream_main"));
    const char* dlsym_error = dlerror();
    if (dlsym_error) {
        LOGI("dlsym error for slipstream_main: %s", dlsym_error);
    }

    if (slipstream_main) {
        LOGI("slipstream_main found, calling with domain=%s, resolvers=%s, port=%d", 
             domain, resolvers, port);
        LOGI("Calling slipstream_main...");
        result = slipstream_main(domain, resolvers, port);
        LOGI("slipstream_main returned: %d", result);
        if (result != 0) {
            LOGE("slipstream_main failed with code: %d", result);
        } else {
            LOGI("slipstream_main completed successfully");
            LOGI("Sleeping for 60 seconds to keep tunnel active...");
            sleep(60);
        }
    } else {
        LOGI("slipstream_main not found, trying main function...");
        
        typedef int (*MainFunc)(int, char**);
        MainFunc main_func = reinterpret_cast<MainFunc>(dlsym(handle, "main"));
        const char* main_dlsym_error = dlerror();
        if (main_dlsym_error) {
            LOGI("dlsym error for main: %s", main_dlsym_error);
        }
        
        if (main_func) {
            std::string portStr = std::to_string(port);
            
            char* argv0 = strdup("slipstream");
            char* argv1 = strdup(domain);
            char* argv2 = strdup(resolvers);
            char* argv3 = strdup("--socks-port");
            char* argv4 = strdup(portStr.c_str());
            
            char* args[] = { argv0, argv1, argv2, argv3, argv4, nullptr };
            
            LOGI("main found, calling with argc=5, argv: %s %s %s %s %s", 
                 args[0], args[1], args[2], args[3], args[4]);
            LOGI("Calling main...");
            result = main_func(5, args);
            LOGI("main returned: %d", result);
            if (result != 0) {
                LOGE("main failed with code: %d", result);
            } else {
                LOGI("main completed successfully");
                LOGI("Sleeping for 60 seconds to keep tunnel active...");
                sleep(60);
            }
            
            free(argv0);
            free(argv1);
            free(argv2);
            free(argv3);
            free(argv4);
        } else {
            LOGE("Neither slipstream_main nor main found in library");
            result = -2;
        }
    }

    // Removed dlclose to keep library loaded
    LOGI("Library kept loaded to maintain tunnel");

    env->ReleaseStringUTFChars(jLibPath, libPath);
    env->ReleaseStringUTFChars(jDomain, domain);
    env->ReleaseStringUTFChars(jResolvers, resolvers);
    env->ReleaseStringUTFChars(jLogFilePath, logFilePath);

    LOGI("=== runSlipstream COMPLETED with result: %d ===", result);
    return result;
}