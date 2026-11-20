// JNI bridge from Nana to the Cactus engine (Apache-2.0), for the Mirage vision model.
// libcactus.a and cactus.h are fetched by CI (see .github/workflows/build.yml), not stored in this repo.
// Text goes back as UTF-8 bytes, not as jstring: JNI strings are "modified UTF-8" and emoji would break them.
#include <jni.h>
#include <cstdio>
#include <string>
#include <vector>
#include "cactus.h"

namespace {
std::string str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray((jsize)s.size());
    if (a) env->SetByteArrayRegion(a, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

std::string lastError(const char* fallback) {
    const char* e = cactus_last_error();
    return std::string("ERR:") + ((e && *e) ? e : fallback);
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahil_chibi_Mirage_nativeLoad(JNIEnv* env, jobject, jstring path) {
    try {
        std::string p = str(env, path);
        FILE* f = std::fopen(p.c_str(), "rb");
        if (!f) return bytes(env, "ERR:cannot open the model file");
        std::fseek(f, 0, SEEK_END);
        long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (n <= 0) { std::fclose(f); return bytes(env, "ERR:the model file is empty"); }
        std::vector<unsigned char> buf((size_t)n);
        size_t got = std::fread(buf.data(), 1, buf.size(), f);
        std::fclose(f);
        if (got != buf.size()) return bytes(env, "ERR:could not read the model file");
        if (cactus_load(buf.data(), (unsigned long long)buf.size()) < 0) return bytes(env, lastError("cactus_load failed"));
        if (!(cactus_models() & CACTUS_VISION)) return bytes(env, "ERR:this file is not a Mirage vision model");
        return bytes(env, "");
    } catch (...) {
        return bytes(env, "ERR:native exception while loading");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sahil_chibi_Mirage_nativeThreads(JNIEnv*, jobject, jint n) {
    cactus_set_threads((int)n);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahil_chibi_Mirage_nativeDescribe(JNIEnv* env, jobject, jbyteArray rgb, jint w, jint h, jstring options, jstring language) {
    try {
        if (!rgb || w <= 0 || h <= 0) return bytes(env, "ERR:bad image");
        jsize n = env->GetArrayLength(rgb);
        if ((long long)n != (long long)w * h * 3) return bytes(env, "ERR:image size does not match width * height * 3");
        std::string opt = str(env, options);
        std::string lang = str(env, language);
        std::vector<char> out(16 * 1024 * 1024);
        jbyte* px = env->GetByteArrayElements(rgb, nullptr);
        if (!px) return bytes(env, "ERR:out of memory");
        int rc = cactus_describe(reinterpret_cast<const unsigned char*>(px), w, h, opt.c_str(),
                                 lang.empty() ? nullptr : lang.c_str(), nullptr, out.data(), (int)out.size());
        env->ReleaseByteArrayElements(rgb, px, JNI_ABORT);
        if (rc < 0) return bytes(env, lastError("cactus_describe failed"));
        out[out.size() - 1] = 0;
        return bytes(env, std::string(out.data()));
    } catch (...) {
        return bytes(env, "ERR:native exception while describing");
    }
}
