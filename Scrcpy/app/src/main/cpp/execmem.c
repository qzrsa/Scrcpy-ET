#include <jni.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <android/log.h>

static const char *TAG = "execmem";

static int my_memfd_create(const char *name, unsigned int flags) {
#ifdef __NR_memfd_create
    return (int) syscall(__NR_memfd_create, name, flags);
#else
    (void) name; (void) flags;
    return -1;
#endif
}

/*
 * 在子进程中通过 memfd_create 把 ELF 字节流写入内存 fd，
 * 再 execve("/proc/self/fd/N", ...) 执行，从而绕过 Android 对
 * app 私有目录的 noexec 挂载限制（无需 root）。
 *
 * 返回值：高 32 位 = 子进程 pid，低 32 位 = 用于读取子进程
 * stdout/stderr 的管道读端 fd。父进程用 ParcelFileDescriptor 接管。
 */
JNIEXPORT jlong JNICALL
Java_qzrs_Scrcpy_easytier_MemfdExec_nativeExec(JNIEnv *env, jclass clazz,
        jbyteArray elf, jobjectArray args) {
    (void) clazz;
    jsize len = (*env)->GetArrayLength(env, elf);
    jbyte *buf = (*env)->GetByteArrayElements(env, elf, NULL);

    int pipefd[2];
    if (pipe(pipefd) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pipe failed: %s", strerror(errno));
        (*env)->ReleaseByteArrayElements(env, elf, buf, JNI_ABORT);
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = (char **) malloc(sizeof(char *) * (argc + 1));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
    }
    argv[argc] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        // 子进程：重定向 stdout/stderr 到管道
        dup2(pipefd[1], 1);
        dup2(pipefd[1], 2);
        close(pipefd[0]);
        close(pipefd[1]);

        int fd = my_memfd_create("ezbin", 0);
        if (fd >= 0) {
            size_t off = 0;
            while (off < (size_t) len) {
                ssize_t w = write(fd, buf + off, (size_t) len - off);
                if (w <= 0) break;
                off += (size_t) w;
            }
            // 关键：execve 需要绝对路径 /proc/self/fd/N
            char path[64];
            snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "child execve: %s", path);
            execve(path, argv, environ);
            // execve 失败才会到这里
            __android_log_print(ANDROID_LOG_ERROR, TAG, "execve failed: %s", strerror(errno));
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "memfd_create failed: %s", strerror(errno));
        }
        _exit(127);
    }

    (*env)->ReleaseByteArrayElements(env, elf, buf, JNI_ABORT);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    close(pipefd[1]);

    if (pid < 0) {
        close(pipefd[0]);
        return -1;
    }

    jlong res = ((jlong) pid << 32) | (jlong) (unsigned int) (unsigned) pipefd[0];
    return res;
}

JNIEXPORT void JNICALL
Java_qzrs_Scrcpy_easytier_MemfdExec_nativeKill(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    kill(pid, SIGKILL);
}

/*
 * 等待子进程结束并返回退出码
 * 返回：子进程退出码（0-255），如果进程被信号终止则返回 128+信号值
 * 出错返回 -1
 */
JNIEXPORT jint JNICALL
Java_qzrs_Scrcpy_easytier_MemfdExec_nativeWait(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status;
    pid_t r = waitpid((pid_t) pid, &status, 0);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
