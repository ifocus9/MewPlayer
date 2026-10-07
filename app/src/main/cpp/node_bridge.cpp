#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int pfd[2];
static pthread_t thr;

static void* log_thread_func(void*) {
    ssize_t rdsz;
    char buf[1024];
    while ((rdsz = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[rdsz - 1] == '\n') --rdsz;
        buf[rdsz] = 0;
        LOGI("%s", buf);
    }
    return nullptr;
}

static int start_redirecting_stdout() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    pipe(pfd);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);

    if (pthread_create(&thr, nullptr, log_thread_func, nullptr) != 0) {
        return -1;
    }
    pthread_detach(thr);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fongmi_android_tv_node_NodeRunner_startNodeWithArguments(
        JNIEnv *env,
        jclass clazz,
        jobjectArray arguments) {

    start_redirecting_stdout();

    int argc = env->GetArrayLength(arguments);
    std::vector<std::string> arg_strings;
    arg_strings.reserve(argc);
    std::vector<char*> argv(argc + 1);

    for (int i = 0; i < argc; i++) {
        jstring str = (jstring) env->GetObjectArrayElement(arguments, i);
        if (str != nullptr) {
            const char *c_str = env->GetStringUTFChars(str, nullptr);
            arg_strings.emplace_back(c_str);
            env->ReleaseStringUTFChars(str, c_str);
        } else {
            arg_strings.emplace_back("");
        }
    }

    // libuv 的 uv_setup_args / process.title 假定 argv 字符串在内存中连续（与 nodejs-mobile 一致），
    // 分散的 std::string 缓冲会让 process.title 写越界，这里打包成一块连续内存。
    size_t total = 0;
    for (const auto &s : arg_strings) total += s.size() + 1;
    std::vector<char> arg_buffer(total > 0 ? total : 1);
    char *cursor = arg_buffer.data();
    for (size_t i = 0; i < arg_strings.size(); i++) {
        size_t len = arg_strings[i].size() + 1;
        memcpy(cursor, arg_strings[i].c_str(), len);
        argv[i] = cursor;
        cursor += len;
    }
    argv[argc] = nullptr;

    LOGI("Starting node with %d arguments...", argc);
    for (int i = 0; i < argc; i++) {
        LOGI("  argv[%d] = %s", i, argv[i]);
    }

    int exit_code = node::Start(argc, argv.data());
    LOGI("Node exited with code: %d", exit_code);
    return exit_code;
}
