// JNI bridge from Nana to llama.cpp (MIT), for a small GGUF chat model that runs on the phone.
// llama.cpp is fetched and built by CI (see .github/workflows/build.yml), not stored in this repo.
// Text goes back as UTF-8 bytes, not jstring (JNI strings are "modified UTF-8" and emoji would break).
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {
std::mutex g_mu;
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
int g_nctx = 3072;
std::atomic<bool> g_stop{false};

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
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahil_chibi_LocalLlm_nativeLoad(JNIEnv* env, jobject, jstring path, jint nctx, jint threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    try {
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        if (g_model) { llama_model_free(g_model); g_model = nullptr; }
        llama_backend_init();
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_mmap = true;
        std::string p = str(env, path);
        g_model = llama_model_load_from_file(p.c_str(), mp);
        if (!g_model) return bytes(env, "ERR:could not read the model file (is it a GGUF?)");
        g_nctx = nctx > 512 ? nctx : 2048;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = (uint32_t)g_nctx;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = threads > 0 ? threads : 4;
        cp.n_threads_batch = cp.n_threads;
        g_ctx = llama_init_from_model(g_model, cp);
        if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return bytes(env, "ERR:not enough memory to start the model"); }
        return bytes(env, "");
    } catch (...) {
        return bytes(env, "ERR:native exception while loading");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sahil_chibi_LocalLlm_nativeStop(JNIEnv*, jobject) { g_stop = true; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sahil_chibi_LocalLlm_nativeGenerate(JNIEnv* env, jobject, jstring prompt, jint maxNew, jfloat temp) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_stop = false;
    try {
        if (!g_model || !g_ctx) return bytes(env, "ERR:model is not loaded");
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        std::string p = str(env, prompt);
        int n = -llama_tokenize(vocab, p.c_str(), (int32_t)p.size(), nullptr, 0, true, true);
        if (n <= 0) return bytes(env, "ERR:could not read the prompt");
        std::vector<llama_token> toks((size_t)n);
        if (llama_tokenize(vocab, p.c_str(), (int32_t)p.size(), toks.data(), (int32_t)toks.size(), true, true) < 0)
            return bytes(env, "ERR:could not read the prompt");
        if (n + maxNew + 4 > g_nctx) return bytes(env, "ERR:the chat is too long for the on-phone model");
        llama_memory_clear(llama_get_memory(g_ctx), true);
        for (int i = 0; i < n; i += 512) {
            if (g_stop) return bytes(env, "ERR:stopped");
            int cnt = n - i < 512 ? n - i : 512;
            llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
            if (llama_decode(g_ctx, b) != 0) return bytes(env, "ERR:the model could not read the prompt");
        }
        llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (temp <= 0.01f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));
        }
        std::string out;
        for (int i = 0; i < maxNew; i++) {
            if (g_stop) break;
            llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            char buf[256];
            int k = llama_token_to_piece(vocab, t, buf, (int32_t)sizeof(buf), 0, false);
            if (k > 0) out.append(buf, (size_t)k);
            llama_batch b = llama_batch_get_one(&t, 1);
            if (llama_decode(g_ctx, b) != 0) break;
        }
        llama_sampler_free(smpl);
        return bytes(env, out);
    } catch (...) {
        return bytes(env, "ERR:native exception while answering");
    }
}
