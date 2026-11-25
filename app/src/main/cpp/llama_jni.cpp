#include <jni.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"

struct Engine { llama_model* model{}; llama_context* ctx{}; std::atomic<bool> cancel{false}; };
static jmethodID token_method, complete_method, error_method;

extern "C" JNIEXPORT jlong JNICALL
Java_com_google_mediapipe_examples_poselandmarker_slm_LlamaNativeBridge_nativeLoadModel(
    JNIEnv* env, jobject, jstring path, jint n_ctx, jint n_threads) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    llama_backend_init();
    auto e = std::make_unique<Engine>();
    auto mp = llama_model_default_params(); mp.use_mmap = true; mp.use_mlock = false;
    e->model = llama_model_load_from_file(chars, mp);
    env->ReleaseStringUTFChars(path, chars);
    if (!e->model) return 0;
    auto cp = llama_context_default_params(); cp.n_ctx = n_ctx; cp.n_batch = 512;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { llama_model_free(e->model); return 0; }
    return reinterpret_cast<jlong>(e.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_mediapipe_examples_poselandmarker_slm_LlamaNativeBridge_nativeGenerateStream(
    JNIEnv* env, jobject, jlong handle, jstring prompt, jint max_tokens, jobject callback) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->ctx) return;
    jclass cls = env->GetObjectClass(callback);
    token_method = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    complete_method = env->GetMethodID(cls, "onComplete", "(IIJJZ)V");
    error_method = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    auto start = std::chrono::steady_clock::now();
    try {
        const char* chars = env->GetStringUTFChars(prompt, nullptr);
        std::string input(chars); env->ReleaseStringUTFChars(prompt, chars);
        llama_kv_cache_clear(e->ctx);
        const llama_vocab* vocab = llama_model_get_vocab(e->model);
        std::vector<llama_token> tokens(input.size() + 16);
        int n = llama_tokenize(vocab, input.c_str(), input.size(), tokens.data(), tokens.size(), true, true);
        if (n < 0) { tokens.resize(-n); n = llama_tokenize(vocab, input.c_str(), input.size(), tokens.data(), tokens.size(), true, true); }
        tokens.resize(n);
        int batch_size = 512;
        for (int offset=0; offset<n; offset+=batch_size) {
            int count = std::min(batch_size, n-offset);
            llama_batch batch = llama_batch_get_one(tokens.data()+offset, count);
            if (llama_decode(e->ctx, batch) != 0) throw std::runtime_error("Fallo en prefill");
        }
        auto sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.85f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.25f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        int out=0; bool first=true; std::string pending; long long ttft=0;
        for (; out<max_tokens && !e->cancel.load(); ++out) {
            llama_token id = llama_sampler_sample(sampler, e->ctx, -1);
            llama_sampler_accept(sampler, id);
            if (first) {
                ttft=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
                first=false;
            }
            if (llama_vocab_is_eog(vocab, id)) break;
            char piece[256]; int len=llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
            if (len>0) {
                pending.append(piece, len);
                // Keep an incomplete UTF-8 code point until a later token completes it.
                size_t valid=0;
                while (valid < pending.size()) {
                    unsigned char lead=static_cast<unsigned char>(pending[valid]);
                    size_t width=(lead & 0x80)==0 ? 1 : (lead & 0xE0)==0xC0 ? 2 : (lead & 0xF0)==0xE0 ? 3 : (lead & 0xF8)==0xF0 ? 4 : 1;
                    if (valid+width > pending.size()) break;
                    valid += width;
                }
                if (valid > 0) {
                    std::string ready=pending.substr(0, valid); pending.erase(0, valid);
                    jstring s=env->NewStringUTF(ready.c_str()); env->CallVoidMethod(callback, token_method, s); env->DeleteLocalRef(s);
                }
            }
            llama_batch batch=llama_batch_get_one(&id, 1);
            if (llama_decode(e->ctx,batch)!=0) break;
        }
        llama_sampler_free(sampler);
        auto end=std::chrono::steady_clock::now();
        auto total=std::chrono::duration_cast<std::chrono::milliseconds>(end-start).count();
        env->CallVoidMethod(callback, complete_method, n, out, (jlong)ttft, (jlong)total, (jboolean)e->cancel.load());
    } catch (const std::exception& ex) {
        jstring msg=env->NewStringUTF(ex.what()); env->CallVoidMethod(callback,error_method,msg); env->DeleteLocalRef(msg);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_mediapipe_examples_poselandmarker_slm_LlamaNativeBridge_nativeCancel(JNIEnv*, jobject, jlong h) {
    if (h) reinterpret_cast<Engine*>(h)->cancel.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_google_mediapipe_examples_poselandmarker_slm_LlamaNativeBridge_nativeResetCancel(JNIEnv*, jobject, jlong h) {
    if (h) reinterpret_cast<Engine*>(h)->cancel.store(false);
}
extern "C" JNIEXPORT void JNICALL
Java_com_google_mediapipe_examples_poselandmarker_slm_LlamaNativeBridge_nativeRelease(JNIEnv*, jobject, jlong h) {
    auto* e=reinterpret_cast<Engine*>(h); if (!e) return;
    llama_free(e->ctx); llama_model_free(e->model); llama_backend_free(); delete e;
}
