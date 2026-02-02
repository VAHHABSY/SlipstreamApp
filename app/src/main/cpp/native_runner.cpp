#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "SlipstreamJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef int (*slipstream_entry_t)(const char*, const char*, int);
typedef int (*main_entry_t)(int, char**);

extern "C"
JNIEXPORT jint JNICALL
Java_net_typeblob_socks_NativeRunner_runSlipstream(JNIEnv* env, jobject /*thiz*/,
                                                   jstring jDomain, jstring jResolvers, jint port) {
    const char* domain = env->GetStringUTFChars(jDomain, nullptr);
    const char* resolvers = env->GetStringUTFChars(jResolvers, nullptr);

    void* handle = dlopen("libslipstream.so", RTLD_NOW);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        env->ReleaseStringUTFChars(jDomain, domain);
        env->ReleaseStringUTFChars(jResolvers, resolvers);
        return -1;
    }

    dlerror(); // clear
    slipstream_entry_t slip = reinterpret_cast<slipstream_entry_t>(dlsym(handle, "slipstream_main"));
    const char* symErr = dlerror();

    int rc = -3;
    if (slip && !symErr) {
        LOGI("Calling slipstream_main(domain=%s, resolvers=%s, port=%d)", domain, resolvers, (int)port);
        rc = slip(domain, resolvers, port);
    } else {
        dlerror();
        main_entry_t mainFn = reinterpret_cast<main_entry_t>(dlsym(handle, "main"));
        const char* symErr2 = dlerror();
        if (mainFn && !symErr2) {
            LOGI("Calling main(argc/argv) fallback");
            std::vector<std::string> args = {"slipstream", domain, resolvers, "--socks-port", std::to_string(port)};
            std::vector<char*> argv;
            argv.reserve(args.size());
            for (auto& s : args) argv.push_back(const_cast<char*>(s.c_str()));
            rc = mainFn((int)argv.size(), argv.data());
        } else {
            LOGE("dlsym failed: %s", symErr2 ? symErr2 : symErr ? symErr : "entry not found");
            rc = -2;
        }
    }

    dlclose(handle);
    env->ReleaseStringUTFChars(jDomain, domain);
    env->ReleaseStringUTFChars(jResolvers, resolvers);
    LOGI("Slipstream finished rc=%d", rc);
    return rc;
}