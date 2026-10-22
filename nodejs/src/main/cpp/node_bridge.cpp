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

namespace {

constexpr const char *kLogTag = "NodeBridge";
constexpr size_t kMaxLogLineBytes = 2048;
constexpr const char *kTruncatedSuffix = " ... [truncated]";

struct LogPipe {
    int descriptor[2]{};
    int priority{};
};

static void write_log_line(LogPipe *pipe, std::string &line, bool truncated) {
    if (truncated) line.append(kTruncatedSuffix);
    if (!line.empty()) __android_log_write(pipe->priority, kLogTag, line.c_str());
}

static void append_log_character(LogPipe *pipe, char character, std::string &line, bool &truncated) {
    if (character == '\n') {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        write_log_line(pipe, line, truncated);
        line.clear();
        truncated = false;
    } else if (line.size() < kMaxLogLineBytes) {
        line.push_back(character);
    } else {
        truncated = true;
    }
}

static void *forward_log(void *value) {
    auto *pipe = static_cast<LogPipe *>(value);
    char buffer[2048];
    std::string line;
    bool truncated = false;
    ssize_t count;
    while ((count = read(pipe->descriptor[0], buffer, sizeof(buffer) - 1)) > 0) {
        for (ssize_t i = 0; i < count; ++i) append_log_character(pipe, buffer[i], line, truncated);
    }
    write_log_line(pipe, line, truncated);
    close(pipe->descriptor[0]);
    delete pipe;
    return nullptr;
}

static void redirect_descriptor(int target, int priority) {
    auto *log_pipe = new LogPipe{};
    log_pipe->priority = priority;
    if (pipe(log_pipe->descriptor) != 0) {
        delete log_pipe;
        return;
    }
    pthread_t thread;
    if (pthread_create(&thread, nullptr, forward_log, log_pipe) != 0) {
        close(log_pipe->descriptor[0]);
        close(log_pipe->descriptor[1]);
        delete log_pipe;
        return;
    }
    pthread_detach(thread);
    dup2(log_pipe->descriptor[1], target);
    close(log_pipe->descriptor[1]);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_fongmi_nodejs_NodeRunner_startNodeWithArguments(
        JNIEnv *env,
        jclass clazz,
        jobjectArray arguments) {

    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    redirect_descriptor(STDOUT_FILENO, ANDROID_LOG_INFO);
    redirect_descriptor(STDERR_FILENO, ANDROID_LOG_ERROR);

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
